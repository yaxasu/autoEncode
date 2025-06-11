package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.s3.notifications.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.stepfunctions.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import java.util.Map;

public class InfrastructureStack extends Stack {
        public InfrastructureStack(final Construct scope, final String id, final StackProps props) {
                super(scope, id, props);

                // Source bucket holds the original media assets
                Bucket sourceBucket = Bucket.Builder.create(this, "AE2SourceBucket")
                                .versioned(true)
                                .lifecycleRules(java.util.List.of(
                                                LifecycleRule.builder()
                                                                .enabled(true)
                                                                .expiration(Duration.days(90))
                                                                .build()))
                                .build();

                // Output bucket for transcoded renditions
                Bucket outputBucket = Bucket.Builder.create(this, "AE2OutputBucket")
                                .versioned(false)
                                .cors(java.util.List.of(CorsRule.builder()
                                                .allowedMethods(java.util.List.of(HttpMethods.GET))
                                                .allowedOrigins(java.util.List.of("*"))
                                                .build()))
                                .build();

                // DynamoDB table that tracks encoding jobs
                Table jobTable = Table.Builder.create(this, "AE2JobTable")
                                .partitionKey(Attribute.builder()
                                                .name("jobId")
                                                .type(AttributeType.STRING)
                                                .build())
                                .billingMode(BillingMode.PAY_PER_REQUEST)
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .build();

                // GSI to query jobs by status from the frontend
                jobTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                                .indexName("status-index")
                                .partitionKey(Attribute.builder()
                                                .name("jobStatus")
                                                .type(AttributeType.STRING)
                                                .build())
                                .projectionType(ProjectionType.ALL)
                                .build());

                // Placeholder Step Functions workflow – a single Pass state
                Pass noOp = Pass.Builder.create(this, "NoOp").build();

                StateMachine sm = StateMachine.Builder.create(this, "AE2TranscodeStateMachine")
                                .definition(noOp)
                                .timeout(Duration.hours(2))
                                .build();

                /*
                 * ------------------------------------------------------------------
                 * Lambda function that submits jobs to Step Functions
                 * ------------------------------------------------------------------
                 */
                Function jobSubmitFn = Function.Builder.create(this, "JobSubmitFn")
                                .functionName("JobSubmitFn") // optional but makes a stable name
                                .runtime(Runtime.JAVA_21)
                                .handler("com.autoencode.backend.JobSubmitHandler::handleRequest") // ← EXACT
                                .code(Code.fromAsset("../backend-jobs/target/backend-jobs-1.0-SNAPSHOT.jar"))
                                .memorySize(2048)
                                .environment(Map.of(
                                                "TABLE_NAME", jobTable.getTableName(),
                                                "STATE_MACHINE_ARN", sm.getStateMachineArn()))
                                .build();

                // Grant the Lambda permissions to read/write the table and start executions
                jobTable.grantReadWriteData(jobSubmitFn);
                sm.grantStartExecution(jobSubmitFn);

                /*
                 * ------------------------------------------------------------------
                 * S3 trigger – fire when an object is uploaded to source/ prefix
                 * ------------------------------------------------------------------
                 */
                sourceBucket.addEventNotification(EventType.OBJECT_CREATED_PUT,
                                new LambdaDestination(jobSubmitFn),
                                NotificationKeyFilter.builder().prefix("source/").build());
        }
}
