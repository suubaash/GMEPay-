{{/*
Shared naming + label helpers. Kept tiny and DRY — every per-service object
is named <release>-<serviceKey> and carries the standard selector labels so a
single _deployment.tpl helper can range over .Values.services.
*/}}

{{- define "gmepay.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Per-service object name: <release>-<serviceKey> */}}
{{- define "gmepay.svcName" -}}
{{- printf "%s-%s" .root.Release.Name .key | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Labels common to every object in the chart. */}}
{{- define "gmepay.commonLabels" -}}
app.kubernetes.io/part-of: gmepay
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{/* Selector labels for one service (stable across upgrades). */}}
{{- define "gmepay.selectorLabels" -}}
app.kubernetes.io/name: {{ .key }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/* Name of the credentials Secret all services pull env from. */}}
{{- define "gmepay.secretName" -}}
{{- printf "%s-credentials" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Name of the non-secret ABI ConfigMap. */}}
{{- define "gmepay.configMapName" -}}
{{- printf "%s-abi-config" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
gmepay.autoscalingEnabled — is an HPA managed for THIS service? -> "true" or "".

Context: dict "svc" <per-service value object> "root" $

Resolution order (explicit, because `default` treats `false` as empty and would
make a per-service `enabled: false` fall through to a `true` fleet default):
  1. services.<svc>.autoscaling.enabled, if the key is present
  2. autoscaling.enabled (the fleet-wide switch)
  3. false

Used by BOTH hpa.yaml (should an HPA exist?) and _deployment.tpl (must the
Deployment OMIT spec.replicas?). Those two answers must never disagree: a
Deployment that keeps a hardcoded `replicas` while an HPA scales it fights the
HPA on every `helm upgrade`, which reads as an autoscaler that "randomly"
resets the replica count.
*/}}
{{- define "gmepay.autoscalingEnabled" -}}
{{- $auto := .svc.autoscaling | default dict -}}
{{- $on := (.root.Values.autoscaling | default dict).enabled | default false -}}
{{- if hasKey $auto "enabled" -}}
{{- $on = $auto.enabled -}}
{{- end -}}
{{- if $on -}}true{{- end -}}
{{- end -}}
