package com.marciorodrigues87.trustedadvisormonitor;

import static com.marciorodrigues87.trustedadvisormonitor.Config.AWS_CREDENTIALS_IDS;
import static com.marciorodrigues87.trustedadvisormonitor.Config.AWS_CREDENTIALS_SECRETS;
import static com.marciorodrigues87.trustedadvisormonitor.Config.AWS_CREDENTIAL_NAMES;
import static com.marciorodrigues87.trustedadvisormonitor.Config.CHECK_PERIOD_MINUTES;
import static com.marciorodrigues87.trustedadvisormonitor.Config.SLACK_HOOK;
import static com.marciorodrigues87.trustedadvisormonitor.Config.SLACK_ICON;
import static com.marciorodrigues87.trustedadvisormonitor.Config.SLACK_USERNAME;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static software.amazon.awssdk.regions.Region.US_EAST_1;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.support.SupportClient;
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorCheckResultRequest;
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorCheckResultResponse;
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorChecksRequest;
import software.amazon.awssdk.services.support.model.DescribeTrustedAdvisorChecksResponse;
import software.amazon.awssdk.services.support.model.TrustedAdvisorCheckDescription;
import software.amazon.awssdk.services.support.model.TrustedAdvisorResourceDetail;

public class Main {

	private static final Map<String, List<String>> PREVIOUS_CHECKS = new ConcurrentHashMap<>();
	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

	public static void main(String[] args) {
		final String[] accounts = AWS_CREDENTIAL_NAMES.asStringArray();
		final String[] keyIds = AWS_CREDENTIALS_IDS.asStringArray();
		final String[] keySecrets = AWS_CREDENTIALS_SECRETS.asStringArray();
		final Map<String, SupportClient> clients = new HashMap<>();
		for (int i = 0; i < accounts.length; i++) {
			final StaticCredentialsProvider provider = StaticCredentialsProvider.create(AwsBasicCredentials.create(keyIds[i], keySecrets[i]));
			clients.put(accounts[i], SupportClient.builder().credentialsProvider(provider).region(US_EAST_1).build());
		}
		Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
			try {
				final int initialChecksSize = PREVIOUS_CHECKS.size();
				for (Entry<String, SupportClient> client : clients.entrySet()) {
					checkAccount(client.getValue(), client.getKey());
				}
				if (initialChecksSize == PREVIOUS_CHECKS.size()) {
					sendSlackMessage("não encontrei mais nenhum recurso dessa vez");
				}
				System.out.println("**** END ****");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, 0, CHECK_PERIOD_MINUTES.asInt(), MINUTES);

		while (true);
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
		final StringBuilder payload = new StringBuilder(format("achei esses recursos gastando dinheiro na conta da AWS do %s\n", account));
		final DescribeTrustedAdvisorChecksRequest request = DescribeTrustedAdvisorChecksRequest.builder()
				.language("en")
				.build();
		final DescribeTrustedAdvisorChecksResponse result = client.describeTrustedAdvisorChecks(request);
		for (TrustedAdvisorCheckDescription check : result.checks()) {
			if (check.category().equalsIgnoreCase("cost_optimizing")) {
				final DescribeTrustedAdvisorCheckResultRequest checkRequest = DescribeTrustedAdvisorCheckResultRequest.builder()
						.checkId(check.id())
						.language("en")
						.build();
				final DescribeTrustedAdvisorCheckResultResponse checkResult = client.describeTrustedAdvisorCheckResult(checkRequest);
				if (checkResult.result().status().equalsIgnoreCase("warning")) {
					if (!checkResult.result().flaggedResources().isEmpty()) {
						payload.append(format("*%s*\n", check.name()));
						for (TrustedAdvisorResourceDetail detail : checkResult.result().flaggedResources()) {
							if (!PREVIOUS_CHECKS.containsKey(detail.resourceId())) {
								PREVIOUS_CHECKS.put(detail.resourceId(), detail.metadata());
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
		sendSlackMessage(payload.toString());
	}

}
