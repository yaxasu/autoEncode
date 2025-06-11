package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
/* S3 */
import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
/* DynamoDB */
import software.amazon.awscdk.services.dynamodb.*;
/* Lambda */
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;
/* IAM */
import software.amazon.awscdk.services.iam.*;
/* Step Functions core */
import software.amazon.awscdk.services.stepfunctions.*;
/* >>> NEW — optimised MediaConvert task <<< */
import software.amazon.awscdk.services.stepfunctions.tasks.MediaConvertCreateJob;
import software.amazon.awscdk.services.stepfunctions.tasks.MediaConvertCreateJobJsonPathProps;
/* MediaConvert template helper */
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

    public InfrastructureStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        /* ──────────────────────────── Buckets ──────────────────────────── */
        Bucket sourceBucket = Bucket.Builder.create(this, "AE2SourceBucket")
                .versioned(true)
                .lifecycleRules(List.of(
                        LifecycleRule.builder().enabled(true).expiration(Duration.days(90)).build()))
                .build();

        Bucket outputBucket = Bucket.Builder.create(this, "AE2OutputBucket")
                .versioned(false)
                .cors(List.of(
                        CorsRule.builder().allowedOrigins(List.of("*"))
                                .allowedMethods(List.of(HttpMethods.GET))
                                .build()))
                .build();

        /* ───────────────────── MediaConvert service role ────────────────── */
        Role mediaConvertRole = Role.Builder.create(this, "MediaConvertServiceRole")
                .assumedBy(new ServicePrincipal("mediaconvert.amazonaws.com"))
                .build();
        sourceBucket.grantRead(mediaConvertRole);
        outputBucket.grantWrite(mediaConvertRole);

        /* ─────────────────────── DynamoDB table ────────────────────────── */
        Table jobTable = Table.Builder.create(this, "AE2JobTable")
                .partitionKey(Attribute.builder().name("jobId").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        jobTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("status-index")
                .partitionKey(Attribute.builder().name("jobStatus").type(AttributeType.STRING).build())
                .projectionType(ProjectionType.ALL)
                .build());

        /* ─────────── Step-Functions: fan-out MediaConvert jobs ─────────── */

        // Map state: one branch per rendition profile
        software.amazon.awscdk.services.stepfunctions.Map map = software.amazon.awscdk.services.stepfunctions.Map.Builder
                .create(this, "ParallelRenditions")
                .itemsPath(JsonPath.stringAt("$.renditions"))
                .maxConcurrency(3)
                .resultPath(JsonPath.DISCARD) // we don’t need the verbose MC response
                .build();

        // >>> optimised MediaConvert task that **waits** for job completion
        MediaConvertCreateJob createJob = MediaConvertCreateJob.jsonPath(
                this, "CreateMediaConvertJob",
                MediaConvertCreateJobJsonPathProps.builder()
                        .createJobRequest(Map.of(
                                "Role", mediaConvertRole.getRoleArn(),
                                "JobTemplate.$", "$.templateName",
                                "Settings", Map.of( // override only the destination
                                        "OutputGroups", List.of(Map.of(
                                                "Name", "CMAF",
                                                "OutputGroupSettings", Map.of(
                                                        "Type", "CMAF_GROUP_SETTINGS",
                                                        "CmafGroupSettings", Map.of(
                                                                "Destination.$",
                                                                String.format(
                                                                        "States.Format('s3://%s/job-%s/{}/', $.jobId, $.outputPrefix)",
                                                                        outputBucket.getBucketName(),
                                                                        "" /* placeholder for {0} in Format */))))))))
                        .integrationPattern(IntegrationPattern.RUN_JOB) // now valid
                        .resultPath("$.mediaConvert")
                        .build());

        map.itemProcessor(createJob);

        StateMachine sm = StateMachine.Builder.create(this, "AE2TranscodeStateMachine")
                .definitionBody(DefinitionBody.fromChainable(map))
                .timeout(Duration.hours(2))
                .build();

        // Allow the state-machine to invoke MediaConvert + S3 I/O
        sm.getRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "mediaconvert:CreateJob",
                        "mediaconvert:GetJob",
                        "mediaconvert:DescribeEndpoints"))
                .resources(List.of("*"))
                .build());
        sourceBucket.grantRead(sm.getRole());
        outputBucket.grantWrite(sm.getRole());

        /* ───────────────────────── Job-submitter Lambda ────────────────── */
        Function jobSubmitFn = Function.Builder.create(this, "JobSubmitFn")
                .functionName("JobSubmitFn")
                .runtime(Runtime.JAVA_21)
                .handler("com.autoencode.backend.JobSubmitHandler::handleRequest")
                .code(Code.fromAsset("../backend-jobs/target/backend-jobs-1.0-SNAPSHOT.jar"))
                .memorySize(2048)
                .environment(Map.of(
                        "TABLE_NAME", jobTable.getTableName(),
                        "STATE_MACHINE_ARN", sm.getStateMachineArn(),
                        "SOURCE_BUCKET", sourceBucket.getBucketName()))
                .build();

        jobTable.grantReadWriteData(jobSubmitFn);
        sm.grantStartExecution(jobSubmitFn);

        sourceBucket.addEventNotification(
                EventType.OBJECT_CREATED_PUT,
                new LambdaDestination(jobSubmitFn),
                NotificationKeyFilter.builder().prefix("source/").build());

        /* ────────── MediaConvert job-templates (three renditions) ───────── */
        createJobTemplate("JobTemplate1080", "AutoEncode2-1080p-h265", "../templates/1080p-h265.json");
        createJobTemplate("JobTemplate720", "AutoEncode2-720p-h264", "../templates/720p-h264.json");
        createJobTemplate("JobTemplate540", "AutoEncode2-540p-h264-qvbr", "../templates/540p-h264-qvbr.json");
    }

    /* Helper: read JSON → Map → CfnJobTemplate */
    private void createJobTemplate(String id, String name, String filePath) {

        Map<String, Object> root;
        try {
            root = MAPPER.readValue(Files.readString(Path.of(filePath)), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("Unable to read/parse " + filePath, e);
        }

        Object settings = root.containsKey("Settings") ? root.get("Settings") : root;

        CfnJobTemplate.Builder.create(this, id)
                .name(name)
                .settingsJson(settings)
                .build();
    }
}
