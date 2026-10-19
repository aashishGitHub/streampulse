# VALIDATE-ONLY. Never planned or applied. Real Kafka runs locally in Docker (KRaft), and the
# emulator used for the other modules does not provide a faithful managed-Kafka control plane.
#
# The services connect to Kafka with a plain bootstrap list and no authentication, so this
# cluster would need a matching client configuration (TLS, and SASL/IAM if enabled) that no
# service has today. Enabling client authentication is therefore left out rather than guessed.

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name}-kafka"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.number_of_broker_nodes

  broker_node_group_info {
    instance_type   = var.instance_type
    client_subnets  = var.client_subnet_ids
    security_groups = var.security_group_ids

    storage_info {
      ebs_storage_info {
        volume_size = var.ebs_volume_size_gib
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  tags = var.tags
}
