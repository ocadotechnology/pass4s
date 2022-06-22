---
sidebar_position: 2
description: Using S3 proxy for large messages
---

# S3 Proxy

Some messaging solutions like SNS/SQS limit the size of the message you can send. S3 proxy solves this particular problem by sending the original payload to S3 bucket and exchaning the pointer to the S3 object on the messanging channel.  

The example below roughly shows how to use the S3 proxy on both consumer and sender.

```scala
val senderConfig =
  S3ProxyConfig
    .Sender
    .withSnsDefaults(bucketName)
    // .copy(
    //   minPayloadSize = Some(0) // You can use custom payload size
    // )
val consumerConfig =
  S3ProxyConfig
    .Consumer
    .withSnsDefaults()
    .copy(
      shouldDeleteAfterProcessing = true // it doesn't by default, just in case there's more listeners
    )
val broker = ??? // let's just asume you already instantiated broker
val payload = Message.Payload("body", Map("foo" -> "bar"))

val sender = 
  broker
    .sender
    .usingS3Proxy(senderConfig)
val consumer = 
  broker
    .consumer(SqsEndpoint(SqsUrl(queueUrl)))
    .usingS3Proxy(consumerConfig)

// no need to know anything about s3 when sending the actual message
val sendMessageOnTopic = sender.sendOne(Message(payload, SnsDestination(SnsArn(topicArn))).widen)
```
