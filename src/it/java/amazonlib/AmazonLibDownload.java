package amazonlib;

import com.amazon.sqs.javamessaging.AmazonSQSExtendedClient;
import com.amazon.sqs.javamessaging.ExtendedClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;

public class AmazonLibDownload {

    public static void main(String[] args) {
        final String BUCKET_NAME = "bucket";
        final String QUEUE_NAME = "http://localhost:4566/000000000000/queue";

        final int EXTENDED_STORAGE_MESSAGE_SIZE_THRESHOLD = 32;

        final AwsClientBuilder.EndpointConfiguration endpoint = new AwsClientBuilder.EndpointConfiguration("http://localhost.localstack.cloud:4566", "us-east-1");
        final AWSStaticCredentialsProvider creds = new AWSStaticCredentialsProvider(new BasicAWSCredentials("d", "d"));
        final AmazonSQS sqsClient = AmazonSQSClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(creds).build();
        final AmazonS3 s3Client = AmazonS3ClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(creds).build();

        final ExtendedClientConfiguration config = new ExtendedClientConfiguration()
                .withPayloadSupportEnabled(s3Client, BUCKET_NAME, false)
                .withPayloadSizeThreshold(EXTENDED_STORAGE_MESSAGE_SIZE_THRESHOLD);
        final AmazonSQSExtendedClient amazonSQSExtendedClient = new AmazonSQSExtendedClient(sqsClient, config);

        final ReceiveMessageResult receiveMessageResult = amazonSQSExtendedClient.receiveMessage(new ReceiveMessageRequest(QUEUE_NAME).withVisibilityTimeout(2));
        System.out.println("Received message is " + receiveMessageResult.getMessages().get(0).getBody());
    }
}
