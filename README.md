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


cd backend-jobs && mvn clean package
cd ../infrastructure && mvn package && cdk deploy

 aws s3 cp sample.mp4 \
  s3://infrastructurestack-ae2sourcebuckete19a7403-4m1rejndojpj/source/sample.mp4