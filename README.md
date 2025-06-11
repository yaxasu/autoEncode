Goals: 
- Process an uploaded mezzanine video 
- fan‑out parallel renditions (1080p H.265, 720p H.264, 540p H.264 QVBR)
- gate on VMAF quality
- deliver CMAF outputs over CloudFront with signed URLs—entirely serverless on AWS.


notes to self:


infrastructure compile and deploy:
mvn -q package
cdk diff
cdk deploy --require-approval never