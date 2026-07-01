{{/*
gmepay.deployment — renders a Deployment + Service for ONE entry of the
.Values.services map. Called once per service from deployments.yaml, so there
is exactly one copy of this manifest in the repo (DRY).

Context passed in by the caller:
  .key   -> the service key (map name, e.g. "api-gateway")
  .svc   -> the per-service value object
  .root  -> the chart root context ($)

Per-service value object shape (see values.yaml for the full schema):
  image           full image ref (registry/repo:tag) OR repo (combined with global.imageRegistry/tag)
  containerPort   int, the port the container listens on (default global.defaultPort)
  replicas        int
  resources       k8s resource block (requests/limits)
  env             map of EXTRA per-service env (merged on top of shared ABI env)
  envSecretKeys   list of Secret keys to surface as env (credentials)
  probe:
    type          "http" (actuator) | "tcp" (socket) | "none"
    path          http path (default global.healthPath, e.g. /actuator/health)
    port          probe port (default containerPort)
  serviceType     ClusterIP (default) | NodePort | LoadBalancer
  ingress         (handled separately in ingress.yaml)
*/}}
{{- define "gmepay.deployment" -}}
{{- $key := .key -}}
{{- $svc := .svc -}}
{{- $root := .root -}}
{{- $port := $svc.containerPort | default $root.Values.global.defaultPort -}}
{{- $ctx := dict "key" $key "root" $root -}}
{{- $probe := $svc.probe | default dict -}}
{{- $probeType := $probe.type | default "tcp" -}}
{{- $probePath := $probe.path | default $root.Values.global.healthPath -}}
{{- $probePort := $probe.port | default $port -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "gmepay.svcName" $ctx }}
  labels:
    {{- include "gmepay.commonLabels" $root | nindent 4 }}
    {{- include "gmepay.selectorLabels" $ctx | nindent 4 }}
    app.kubernetes.io/component: {{ $svc.component | default "backend" }}
spec:
  replicas: {{ $svc.replicas | default $root.Values.global.defaultReplicas }}
  selector:
    matchLabels:
      {{- include "gmepay.selectorLabels" $ctx | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "gmepay.selectorLabels" $ctx | nindent 8 }}
        app.kubernetes.io/component: {{ $svc.component | default "backend" }}
      annotations:
        # Roll pods when the ABI config or secrets change.
        checksum/abi-config: {{ include (print $root.Template.BasePath "/configmap.yaml") $root | sha256sum }}
    spec:
      {{- with $root.Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ $key }}
          image: {{ include "gmepay.image" (dict "svc" $svc "root" $root) }}
          imagePullPolicy: {{ $svc.imagePullPolicy | default $root.Values.global.imagePullPolicy }}
          ports:
            - name: http
              containerPort: {{ $port }}
          # Shared, non-secret ABI config (endpoints, regions, flags) for every pod.
          envFrom:
            - configMapRef:
                name: {{ include "gmepay.configMapName" $root }}
          env:
            {{- /* Credentials from the K8s Secret, surfaced under the SAME name as the secret key. */}}
            {{- range $svc.envSecretKeys }}
            - name: {{ . }}
              valueFrom:
                secretKeyRef:
                  name: {{ include "gmepay.secretName" $root }}
                  key: {{ . }}
            {{- end }}
            {{- /* Secret values surfaced under a DIFFERENT env name (envName: secretKey). */}}
            {{- range $envName, $secretKey := $svc.envSecretAliases }}
            - name: {{ $envName }}
              valueFrom:
                secretKeyRef:
                  name: {{ include "gmepay.secretName" $root }}
                  key: {{ $secretKey }}
            {{- end }}
            {{- /* Per-service literal env (base URLs, feature flags, datasource URL). */}}
            {{- range $k, $v := $svc.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
          {{- if $svc.resources }}
          resources:
            {{- toYaml $svc.resources | nindent 12 }}
          {{- else if $root.Values.global.defaultResources }}
          resources:
            {{- toYaml $root.Values.global.defaultResources | nindent 12 }}
          {{- end }}
          {{- if eq $probeType "http" }}
          livenessProbe:
            httpGet:
              path: {{ $probePath }}
              port: {{ $probePort }}
            initialDelaySeconds: {{ $probe.initialDelaySeconds | default 20 }}
            periodSeconds: {{ $probe.periodSeconds | default 10 }}
            timeoutSeconds: {{ $probe.timeoutSeconds | default 3 }}
            failureThreshold: {{ $probe.failureThreshold | default 6 }}
          readinessProbe:
            httpGet:
              path: {{ $probePath }}
              port: {{ $probePort }}
            initialDelaySeconds: {{ $probe.initialDelaySeconds | default 15 }}
            periodSeconds: {{ $probe.periodSeconds | default 10 }}
            timeoutSeconds: {{ $probe.timeoutSeconds | default 3 }}
            failureThreshold: {{ $probe.failureThreshold | default 6 }}
          {{- else if eq $probeType "tcp" }}
          {{- /* Most services do NOT ship spring-boot-starter-actuator; a TCP
                 socket probe on the listen port is the correct liveness signal
                 (mirrors docker-compose x-tcp-health). */}}
          livenessProbe:
            tcpSocket:
              port: {{ $probePort }}
            initialDelaySeconds: {{ $probe.initialDelaySeconds | default 20 }}
            periodSeconds: {{ $probe.periodSeconds | default 10 }}
            failureThreshold: {{ $probe.failureThreshold | default 6 }}
          readinessProbe:
            tcpSocket:
              port: {{ $probePort }}
            initialDelaySeconds: {{ $probe.initialDelaySeconds | default 15 }}
            periodSeconds: {{ $probe.periodSeconds | default 10 }}
            failureThreshold: {{ $probe.failureThreshold | default 6 }}
          {{- end }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "gmepay.svcName" $ctx }}
  labels:
    {{- include "gmepay.commonLabels" $root | nindent 4 }}
    {{- include "gmepay.selectorLabels" $ctx | nindent 4 }}
spec:
  type: {{ $svc.serviceType | default "ClusterIP" }}
  selector:
    {{- include "gmepay.selectorLabels" $ctx | nindent 4 }}
  ports:
    - name: http
      port: {{ $port }}
      targetPort: http
      protocol: TCP
{{- end -}}

{{/* Resolve a full image ref. If svc.image contains a "/" treat it as a full
     ref; otherwise prefix global.imageRegistry and suffix global.imageTag. */}}
{{- define "gmepay.image" -}}
{{- $svc := .svc -}}
{{- $g := .root.Values.global -}}
{{- $img := $svc.image -}}
{{- if contains "/" $img -}}
{{- if contains ":" $img -}}
{{- $img -}}
{{- else -}}
{{- printf "%s:%s" $img ($svc.imageTag | default $g.imageTag) -}}
{{- end -}}
{{- else -}}
{{- printf "%s/%s:%s" $g.imageRegistry $img ($svc.imageTag | default $g.imageTag) -}}
{{- end -}}
{{- end -}}
