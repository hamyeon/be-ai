resource "aws_sqs_queue" "dlq" {
  name                    = "${var.name_prefix}-queue-dlq"
  sqs_managed_sse_enabled = true

  tags = {
    Name = "${var.name_prefix}-queue-dlq"
  }
}

resource "aws_sqs_queue" "main" {
  name                    = "${var.name_prefix}-queue"
  sqs_managed_sse_enabled = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = var.sqs_max_receive_count
  })

  tags = {
    Name = "${var.name_prefix}-queue"
  }
}
