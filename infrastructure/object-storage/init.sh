#!/bin/sh
set -eu
awslocal s3api head-bucket --bucket billing-invoices 2>/dev/null || awslocal s3api create-bucket --bucket billing-invoices
