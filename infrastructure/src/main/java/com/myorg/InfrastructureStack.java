/* ────────────────────────────────────────────────────────────────
 *  InfrastructureStack.java — fully revised, ASL-clean
 * ──────────────────────────────────────────────────────────────── */
package com.myorg;

import software.constructs.Construct;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;

/* S3 */
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.CorsRule;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.amazon.awscdk.services.s3.NotificationKeyFilter;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
/* CloudWatch */
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
/* DynamoDB */
import software.amazon.awscdk.services.dynamodb.*;
/* Lambda */
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.ILayerVersion;
import software.amazon.awscdk.services.lambda.Architecture;
/* IAM */
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
/* Step Functions */
import software.amazon.awscdk.services.stepfunctions.*;
import software.amazon.awscdk.services.stepfunctions.tasks.*;
/* MediaConvert */
import software.amazon.awscdk.services.mediaconvert.CfnJobTemplate;

/* Misc */
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class InfrastructureStack extends Stack {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Layer that contains FFmpeg 6.1.1 + libvmaf (arm64). */
    private static final String FFMPEG_VMAF_LAYER_ARN =
        "arn:aws:lambda:ca-central-1:764668379002:layer:AE2-ffmpeg-vmaf-arm64:2";

    public InfrastructureStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        /* ─────────────── S3 buckets ─────────────── */
        Bucket logBucket = Bucket.Builder.create(this, "AE2LogBucket")
            .encryption(BucketEncryption.S3_MANAGED)
            .removalPolicy(RemovalPolicy.RETAIN)
            .build();

        Bucket sourceBucket = Bucket.Builder.create(this, "AE2SourceBucket")
            .versioned(true)
            .encryption(BucketEncryption.S3_MANAGED)
            .serverAccessLogsBucket(logBucket)
            .lifecycleRules(List.of(
                LifecycleRule.builder()
                    .enabled(true)
                    .expiration(Duration.days(90))
                    .build()))
            .build();

        Bucket outputBucket = Bucket.Builder.create(this, "AE2OutputBucket")
            .versioned(false)
            .encryption(BucketEncryption.S3_MANAGED)
            .serverAccessLogsBucket(logBucket)
            .cors(List.of(
                CorsRule.builder()
                    .allowedOrigins(List.of("*"))
                    .allowedMethods(List.of(
                        software.amazon.awscdk.services.s3.HttpMethods.GET))
                    .build()))
            .lifecycleRules(List.of(
                LifecycleRule.builder()
                    .enabled(true)
                    .expiration(Duration.days(90))
                    .build()))
            .build();

        /* ─────────── MediaConvert role ─────────── */
        Role mediaConvertRole = Role.Builder.create(this, "MediaConvertServiceRole")
            .assumedBy(new ServicePrincipal("mediaconvert.amazonaws.com"))
            .build();
        sourceBucket.grantRead(mediaConvertRole, "source/*");
        outputBucket.grantWrite(mediaConvertRole);

        /* ─────────── DynamoDB table ─────────── */
        Table jobTable = Table.Builder.create(this, "AE2JobTable")
            .partitionKey(Attribute.builder().name("jobId").type(AttributeType.STRING).build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.RETAIN)
            .build();
        jobTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
            .indexName("status-index")
            .partitionKey(Attribute.builder().name("jobStatus").type(AttributeType.STRING).build())
            .projectionType(ProjectionType.ALL)
            .build());

        /* ─────────── Lambda layer ─────────── */
        ILayerVersion ffmpegLayer = LayerVersion.fromLayerVersionArn(
            this, "FfmpegVmafLayer", FFMPEG_VMAF_LAYER_ARN);

        /* ─────────── Job-submit Lambda ─────────── */
        Function jobSubmitFn = Function.Builder.create(this, "JobSubmitFn")
            .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
            .functionName("JobSubmitFn")
            .handler("com.autoencode.backend.JobSubmitHandler::handleRequest")
            .code(Code.fromAsset("../backend-jobs/target/backend-jobs-1.0-SNAPSHOT.jar"))
            .memorySize(1024)
            .environment(Map.of(
                "TABLE_NAME", jobTable.getTableName(),
                "SOURCE_BUCKET", sourceBucket.getBucketName()))
            .build();
        jobTable.grantReadWriteData(jobSubmitFn);
        sourceBucket.addEventNotification(
            EventType.OBJECT_CREATED_PUT,
            new LambdaDestination(jobSubmitFn),
            NotificationKeyFilter.builder().prefix("source/").build());

        /* ─────────── VMAF Lambda ─────────── */
        Function vmafFn = Function.Builder.create(this, "VmafQualityFn")
            .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21)
            .architecture(Architecture.ARM_64)
            .functionName("VmafQualityFn")
            .handler("com.autoencode.backend.VmafQualityFn::handleRequest")
            .code(Code.fromAsset("../backend-jobs/target/backend-jobs-1.0-SNAPSHOT.jar"))
            .layers(List.of(ffmpegLayer))
            .memorySize(3008)
            .timeout(Duration.minutes(8))
            .environment(Map.of("TABLE_NAME", jobTable.getTableName()))
            .build();
        jobTable.grantWriteData(vmafFn);
        sourceBucket.grantRead(vmafFn, "source/*");
        outputBucket.grantRead(vmafFn);

        /* ─────────── Step-Functions tasks ─────────── */

        Map<String, String> itemSelector = Map.of(
            "jobId.$",        "$.jobId",
            "sourceBucket.$", "$.sourceBucket",
            "sourceKey.$",    "$.sourceKey",
            "outputBucket",   outputBucket.getBucketName(),
            "templateName.$", "$$.Map.Item.Value.templateName",
            "outputPrefix.$", "$$.Map.Item.Value.outputPrefix",
            "threshold.$",    "$$.Map.Item.Value.threshold"
        );

        software.amazon.awscdk.services.stepfunctions.Map parallelRenditions =
            software.amazon.awscdk.services.stepfunctions.Map.Builder
                .create(this, "ParallelRenditions")
                .itemsPath(JsonPath.stringAt("$.renditions"))
                .itemSelector(itemSelector)
                .maxConcurrency(3)
                .resultPath(JsonPath.DISCARD)
                .build();

        MediaConvertCreateJob createJob = MediaConvertCreateJob.jsonPath(
            this, "CreateMCJob",
            MediaConvertCreateJobJsonPathProps.builder()
                .integrationPattern(IntegrationPattern.RUN_JOB)
                .createJobRequest(Map.of(
                    "Role", mediaConvertRole.getRoleArn(),
                    "JobTemplate.$", "$.templateName",
                    "Settings", Map.of(
                        "Inputs", List.of(Map.of(
                            "FileInput.$",
                            "States.Format('s3://{}/{}', $.sourceBucket, $.sourceKey)")),
                        "OutputGroups", List.of(Map.of(
                            "Name", "CMAF",
                            "OutputGroupSettings", Map.of(
                                "Type", "CMAF_GROUP_SETTINGS",
                                "CmafGroupSettings", Map.of(
                                    "Destination.$",
                                    "States.Format('s3://{}/job-{}/{}/', $.outputBucket, $.jobId, $.outputPrefix)")))))))
                .resultPath("$.mc")
                .build());

        createJob.addRetry(RetryProps.builder()
            .errors(List.of("States.ALL"))
            .interval(Duration.seconds(5))
            .backoffRate(2)
            .maxAttempts(3)
            .build());

        /* ─────────── SelectPlaylist (fixed JSONPath) ─────────── */

        String fileNameExpr =
            "States.ArrayGetItem(" +
                "States.StringSplit($.sourceKey,'/')," +
                "States.MathAdd(States.ArrayLength(States.StringSplit($.sourceKey,'/')),-1))";

        String baseNameExpr =
            "States.ArrayGetItem(" +
                "States.StringSplit(" + fileNameExpr + " ,'.'),0)";

        Pass selectPlaylist = Pass.Builder.create(this, "SelectPlaylist")
            .parameters(Map.of(
                "fileName.$",     fileNameExpr,
                "baseName.$",     baseNameExpr,
                "dest.$",         "$.mc.Job.Settings.OutputGroups[0].OutputGroupSettings.CmafGroupSettings.Destination",
                "renditionUri.$", "States.Format('{}{}_{}p.m3u8', " +
                                  "$.mc.Job.Settings.OutputGroups[0].OutputGroupSettings.CmafGroupSettings.Destination, " +
                                  baseNameExpr + ", $.outputPrefix)"
            ))
            .resultPath("$.file")
            .build();

        /* ─────────── VMAF calculation ─────────── */
        LambdaInvoke vmafTask = LambdaInvoke.Builder.create(this, "CalcVMAF")
            .lambdaFunction(vmafFn)
            .payload(TaskInput.fromObject(Map.of(
                "jobId.$",        "$.jobId",
                "sourceBucket.$", "$.sourceBucket",
                "sourceKey.$",    "$.sourceKey",
                "renditionUri.$", "$.file.renditionUri",
                "threshold.$",    "$.threshold")))
            .resultPath("$.quality")
            .build();

        Pass qualityOk = Pass.Builder.create(this, "QualityOK").build();
        Choice gate = Choice.Builder.create(this, "QualityGate").build();
        gate.when(Condition.booleanEquals("$.quality.passed", true), qualityOk)
            .otherwise(new Fail(this, "QualityFailed",
                FailProps.builder().cause("VMAF below threshold").build()));

        parallelRenditions.itemProcessor(
            createJob.next(selectPlaylist).next(vmafTask).next(gate));

        /* ─────────── State Machine + logging ─────────── */
        LogGroup smLog = LogGroup.Builder.create(this, "AE2StateMachineLogs")
            .retention(RetentionDays.ONE_MONTH)
            .build();

        StateMachine sm = StateMachine.Builder.create(this, "AE2TranscodeStateMachine")
            .definitionBody(DefinitionBody.fromChainable(parallelRenditions))
            .timeout(Duration.hours(3))
            .logs(LogOptions.builder()
                .destination(smLog)
                .includeExecutionData(true)
                .build())
            .build();

        jobSubmitFn.addEnvironment("STATE_MACHINE_ARN", sm.getStateMachineArn());
        sm.grantStartExecution(jobSubmitFn);

        /* Permissions for State-Machine */
        sm.getRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
            .actions(List.of(
                "mediaconvert:CreateJob",
                "mediaconvert:GetJob",
                "mediaconvert:DescribeEndpoints",
                "iam:PassRole"))
            .resources(List.of("*"))
            .build());
        sm.getRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
            .actions(List.of("iam:PassRole"))
            .resources(List.of(mediaConvertRole.getRoleArn()))
            .build());

        sourceBucket.grantRead(sm.getRole(), "source/*");
        outputBucket.grantWrite(sm.getRole());

        /* ─────────── MediaConvert job-templates ─────────── */
        createJobTemplate("JobTemplate1080", "AutoEncode2-1080p-h265",     "../templates/1080p-h265.json");
        createJobTemplate("JobTemplate720",  "AutoEncode2-720p-h264",      "../templates/720p-h264.json");
        createJobTemplate("JobTemplate540",  "AutoEncode2-540p-h264-qvbr", "../templates/540p-h264-qvbr.json");
    }

    /* Helper – create MediaConvert job templates from JSON files */
    private void createJobTemplate(String id, String name, String filePath) {
        Map<String,Object> root;
        try {
            root = MAPPER.readValue(Files.readString(Path.of(filePath)), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Cannot read " + filePath, e);
        }
        Object settings = root.containsKey("Settings") ? root.get("Settings") : root;
        CfnJobTemplate.Builder.create(this, id)
            .name(name)
            .settingsJson(settings)
            .build();
    }
}
