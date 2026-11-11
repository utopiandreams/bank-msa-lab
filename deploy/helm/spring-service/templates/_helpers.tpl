{{- define "spring-service.name" -}}
{{- required "values.name이 필요합니다" .Values.name -}}
{{- end -}}

{{/* Service가 파드를 고르는 라벨. 한 번 배포하면 바꿀 수 없으니 최소한으로 둔다 */}}
{{- define "spring-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "spring-service.name" . }}
{{- end -}}

{{- define "spring-service.labels" -}}
{{ include "spring-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/part-of: bank-msa-lab
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
