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
