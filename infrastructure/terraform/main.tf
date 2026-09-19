terraform {
  required_version = ">= 1.5.0"
  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.25.0"
    }
  }
}

resource "kubernetes_namespace" "aurora_system" {
  metadata {
    name = var.namespace
    labels = {
      "app.kubernetes.io/name"       = "aurora"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }
}
