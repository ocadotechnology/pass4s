---
sidebar_position: 6
description: Local SNS/SQS development with Localstack
---

# Localstack

If you want to use pass4s for SNS/SQS messaging, your usual first step is to set up local development environment. [Localstack](https://docs.localstack.cloud/) is a toolkit provided by Amazon to replicate AWS locally. This section provides ready-to-use localstack setup using [docker-compose](https://docs.docker.com/compose/).

## Docker compose setup

To launch localstack simply copy the snippet below and save it locally as `docker-compose.yml`. Once that's done, open terminal, navigate to the place where you saved the file and run `docker-compose up`. Your setup is ready to go.

```yml
version: "3.8"

services:
  localstack:
    container_name: localstack_main
    image: localstack/localstack
    hostname: localhost.localstack.cloud
    ports:
      - "127.0.0.1:4566:4566"            # LocalStack Gateway
      - "127.0.0.1:4510-4559:4510-4559"  # external services port range
    environment:
      - DOCKER_HOST=unix:///var/run/docker.sock
      - SERVICES=sqs,sns
      - EAGER_SERVICE_LOADING=1
      - SKIP_SSL_CERT_DOWNLOAD=1
      - HOSTNAME_EXTERNAL=localhost.localstack.cloud
    volumes:
      - "/tmp/localstack:/var/lib/localstack"
      - "/var/run/docker.sock:/var/run/docker.sock"
  
  setup-resources:
    image: localstack/localstack
    environment:
      - AWS_ACCESS_KEY_ID=test
      - AWS_SECRET_ACCESS_KEY=AWSSECRET
      - AWS_DEFAULT_REGION=eu-west-2
    entrypoint: /bin/sh -c
    command: >
      "
        sleep 15
        alias aws='aws --endpoint-url http://localstack:4566'
        # Executing SNS
        aws sns create-topic --name local_sns
        # Executing SQS
        aws sqs create-queue --queue-name local_queue
        # Subscribing to SNS to SQS
        aws sns subscribe --attributes 'RawMessageDelivery=true' --topic-arn arn:aws:sns:eu-west-2:000000000000:local_sns --protocol sqs --notification-endpoint arn:aws:sqs:eu-west-2:000000000000:local_queue
        aws sqs get-queue-url --queue-name local_queue
        # Create na S3 bucket for large messages
        aws s3 mb s3://large-messages
      "      
    depends_on:
      - localstack
```

## Resources

This setup comes with batteries included, meaning it not only does set up the service, but also some basic resources for you to play with. The provided resources are:

 - Topic `local_sns` to write your messages onto
 - Queue `local_queue` to read messages from
 - Subscription between the two, making the messages from `local_sns` to be pushed to `local_queue`
 - Bucket `large-messages` in case you want to use [s3 proxy](modules/s3proxy)

Please notice how for queue creation we use `--attributes 'RawMessageDelivery=true'`. This is done intentionally, make sure to use this attribute with your production setup to avoid communication issues.
