---
sidebar_position: 5
description: How to upgrade
---

# Migration guide

## 0.4.0

### Intro

In this version we fix compatibility with the Amazon
[SNS](https://github.com/awslabs/amazon-sns-java-extended-client-lib)/[SQS](https://github.com/awslabs/amazon-sqs-java-extended-client-lib)
Extended Client Library. The bug was that when uploading content to S3, the JSON format of the message sent to the
broker was incompatible with the Amazon's Extended Client.

* Legacy format
```json
{"s3BucketName": "bucket", "s3Key": "key"}
```
* Correct format
```json
["software.amazon.payloadoffloading.PayloadS3Pointer", {"s3BucketName": "bucket", "s3Key": "key"}]
```

### How to migrate

1. Deploy the new version of the library in all consumers (replace `usingS3Proxy` with `usingS3ProxyForBigPayload`).
   The new consumer is compatible with both formats.
2. Deploy the new version of the library in all producers (replace `usingS3Proxy` with `usingS3ProxyForBigPayload`).
   The new producer will send the message in the correct format.
3. If you are having scenario where an application is both consumer and producer, then for consuming follow point 1
   and for producing use firstly `usingS3ProxyLegacyEncoding` and then in the next release migrate
   to `usingS3ProxyForBigPayload`