variable "namespace" {
  type        = string
  default     = "aurora-system"
  description = "Kubernetes namespace for AURORA components"
}

variable "environment" {
  type        = string
  default     = "production"
  description = "Deployment target environment (staging/production)"
}
