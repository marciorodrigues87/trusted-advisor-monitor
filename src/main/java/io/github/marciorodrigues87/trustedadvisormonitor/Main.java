package io.github.marciorodrigues87.trustedadvisormonitor;

import static io.github.marciorodrigues87.trustedadvisormonitor.Config.AWS_CREDENTIALS_IDS;
import static io.github.marciorodrigues87.trustedadvisormonitor.Config.AWS_CREDENTIALS_SECRETS;
import static io.github.marciorodrigues87.trustedadvisormonitor.Config.AWS_CREDENTIAL_NAMES;
import static io.github.marciorodrigues87.trustedadvisormonitor.Config.SLACK_HOOK;
import static io.github.marciorodrigues87.trustedadvisormonitor.Config.SLACK_ICON;
import static io.github.marciorodrigues87.trustedadvisormonitor.Config.SLACK_USERNAME;
import static java.lang.String.format;
import static software.amazon.awssdk.regions.Region.US_EAST_1;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.support.SupportClient;
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorCheckResultRequest;
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorCheckResultResponse;
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorChecksRequest;
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorChecksResponse;
import software.amazon.awssdk.services.support.model.RefreshTrustedAdvisorCheckRequest;
import software.amazon.awssdk.services.support.model.TrustedAdvisorCheckDescription;
import software.amazon.awssdk.services.support.model.TrustedAdvisorResourceDetail;

public class Main {

	private static final PreviousChecks PREVIOUS_CHECKS = new PreviousChecks();
	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

	public static void main(String[] args) {
		final String[] accounts = AWS_CREDENTIAL_NAMES.asStringArray();
		final String[] keyIds = AWS_CREDENTIALS_IDS.asStringArray();
		final String[] keySecrets = AWS_CREDENTIALS_SECRETS.asStringArray();
		for (int i = 0; i < accounts.length; i++) {
			try {
				final StaticCredentialsProvider provider = StaticCredentialsProvider.create(AwsBasicCredentials.create(keyIds[i], keySecrets[i]));
				checkAccount(SupportClient.builder().credentialsProvider(provider).region(US_EAST_1).build(), accounts[i]);
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
		System.out.println("**** END ****");

	}

	private static void sendSlackMessage(String payload) throws IOException, InterruptedException {
		final HttpRequest slackPost = HttpRequest.newBuilder()
				.uri(URI.create(SLACK_HOOK.asString()))
				.POST(BodyPublishers.ofString(format("{\"username\":\"%s\",\"icon_emoji\":\"%s\",\"text\": \"%s\"}",
						SLACK_USERNAME.asString(), SLACK_ICON.asString(), payload)))
				.build();
		final String bodyResponse = HTTP_CLIENT.send(slackPost, BodyHandlers.ofString()).body();
		System.out.println(format("**** SLACK %s ****", bodyResponse));
	}

	private static void checkAccount(SupportClient client, String account) throws IOException, InterruptedException {
		boolean hasNewChecks = false;
		final StringBuilder payload = new StringBuilder(format("achei esses recursos gastando dinheiro na conta da AWS do %s\n", account));
		final DescribeTrustedAdvisorChecksRequest request = DescribeTrustedAdvisorChecksRequest.builder()
				.language("en")
				.build();
		final DescribeTrustedAdvisorChecksResponse result = client.describeTrustedAdvisorChecks(request);
		for (TrustedAdvisorCheckDescription check : result.checks()) {
			if (check.category().equalsIgnoreCase("cost_optimizing")) {
				try {
					final RefreshTrustedAdvisorCheckRequest refreshTrustedAdvisorCheckRequest = RefreshTrustedAdvisorCheckRequest.builder().checkId(check.id()).build();
					client.refreshTrustedAdvisorCheck(refreshTrustedAdvisorCheckRequest);
				} catch (Exception e) {
					e.printStackTrace();
				}
				final DescribeTrustedAdvisorCheckResultRequest checkRequest = DescribeTrustedAdvisorCheckResultRequest.builder()
						.checkId(check.id())
						.language("en")
						.build();
				final DescribeTrustedAdvisorCheckResultResponse checkResult = client.describeTrustedAdvisorCheckResult(checkRequest);
				if (checkResult.result().status().equalsIgnoreCase("warning")) {
					if (!checkResult.result().flaggedResources().isEmpty()) {
						boolean titleAdded = false;
						for (TrustedAdvisorResourceDetail detail : checkResult.result().flaggedResources()) {
							if (!PREVIOUS_CHECKS.contains(detail.resourceId())) {
								PREVIOUS_CHECKS.add(detail.resourceId());
								hasNewChecks = true;
								if (!titleAdded) {
									payload.append(format("*%s*\n", check.name()));
									titleAdded = true;
								}
								switch (check.name()) {
								case "Low Utilization Amazon EC2 Instances": {
									final String region = detail.metadata().get(0);
									final String id = detail.metadata().get(1);
									final String name = detail.metadata().get(2);
									final String savings = detail.metadata().get(4);
									payload.append(format("%s >> %s >> %s >> *%s*\n", region, id, name, savings));
									break;
								}
								case "Idle Load Balancers": {
									final String region = detail.metadata().get(0);
									final String id = detail.metadata().get(1);
									final String savings = detail.metadata().get(3);
									payload.append(format("%s >> %s >> *%s*\n", region, id, savings));
									break;
								}
								case "Underutilized Amazon EBS Volumes": {
									final String region = detail.metadata().get(0);
									final String id = detail.metadata().get(1);
									final String name = detail.metadata().get(2);
									final String savings = detail.metadata().get(5);
									payload.append(format("%s >> %s >> %s >> *%s*\n", region, id, name, savings));
									break;
								}
								case "Amazon RDS Idle DB Instances": {
									final String region = detail.metadata().get(0);
									final String id = detail.metadata().get(1);
									final String savings = detail.metadata().get(6);
									payload.append(format("%s >> %s >> *%s*\n", region, id, savings));
									break;
								}
								default:
									final String region = detail.metadata().get(0);
									final String id = detail.metadata().get(1);
									payload.append(format("%s >> `%s`\n", region, id));
								}
							}
						}
					}
				}
			}
		}
		if (hasNewChecks) {
			sendSlackMessage(payload.toString());
		} else {
			sendSlackMessage(format("não encontrei nenhum recurso no conta da AWS do %s dessa vez :eyes:", account));
		}
	}

}
