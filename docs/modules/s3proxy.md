---
sidebar_position: 2
description: Using S3 proxy for large messages
---

# S3 Proxy

Some messaging solutions like SNS/SQS limit the size of the message you can send. S3 proxy solves this particular problem by sending the original payload to S3 bucket and exchanging the pointer to the S3 object on the messaging channel.  

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
val broker = ??? // let's just assume you already instantiated broker
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

Please note that for this to work you need to have an existing s3 bucket, pass4s doesn't create any AWS resources on it's own. If you are using the [provided localstack setup](../localstack) you can use the `s3://large-messages` bucket as a playground.

For more detailed examples on s3 proxy, you might want to check out the following article https://blog.michalp.net/posts/scala/pass4s-s3-proxy/
