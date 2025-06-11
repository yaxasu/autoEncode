#!/usr/bin/env bash
set -euo pipefail

PROFILE=${AWS_PROFILE:-default}
REGION=${AWS_REGION:-ca-central-1}

aws --profile "$PROFILE" --region "$REGION" mediaconvert create-job-template \
  --name AutoEncode2-1080p-h265 \
  --settings file://templates/1080p-h265.json

aws --profile "$PROFILE" --region "$REGION" mediaconvert create-job-template \
  --name AutoEncode2-720p-h264 \
  --settings file://templates/720p-h264.json

aws --profile "$PROFILE" --region "$REGION" mediaconvert create-job-template \
  --name AutoEncode2-540p-h264-qvbr \
  --settings file://templates/540p-h264-qvbr.json
