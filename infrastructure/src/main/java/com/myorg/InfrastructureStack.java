package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import software.amazon.awscdk.services.s3.*;

public class InfrastructureStack extends Stack {
    public InfrastructureStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Bucket sourceBucket = Bucket.Builder.create(this, "AE2SourceBucket")
                .versioned(true)
                .lifecycleRules(java.util.List.of(
                        LifecycleRule.builder()
                                .enabled(true)
                                .expiration(Duration.days(90))
                                .build()))
                .build();

        Bucket outputBucket = Bucket.Builder.create(this, "AE2OutputBucket")
                .versioned(false)
                .cors(java.util.List.of(CorsRule.builder()
                        .allowedMethods(java.util.List.of(HttpMethods.GET))
                        .allowedOrigins(java.util.List.of("*"))
                        .build()))
                .build();
    }
}