resource "aws_security_group" "this" {
  name        = "${var.name_prefix}-sg"
  description = "tf-dev baseline security group: no ingress rules, outbound allowed"
  vpc_id      = aws_vpc.this.id

  egress {
    description = "allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.name_prefix}-sg"
  }
}
