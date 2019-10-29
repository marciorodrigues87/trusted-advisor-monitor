package io.github.marciorodrigues87.trustedadvisormonitor;

import static io.github.marciorodrigues87.trustedadvisormonitor.Config.PREVIOUS_RESULTS_S3_BUCKET;
import static software.amazon.awssdk.regions.Region.US_EAST_1;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class PreviousChecks {

	private static final S3Client S3 = S3Client.builder().region(US_EAST_1).build();
	
	public boolean contains(String key) {
		final HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(PREVIOUS_RESULTS_S3_BUCKET.asString()).key(key).build();
		try {
			S3.headObject(headObjectRequest);
		} catch (NoSuchKeyException e) {
			return false;
		}
		return true;
	}

	public void add(String key) {
		final PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(PREVIOUS_RESULTS_S3_BUCKET.asString()).key(key).build();
		S3.putObject(putObjectRequest, RequestBody.empty());
	}

}
