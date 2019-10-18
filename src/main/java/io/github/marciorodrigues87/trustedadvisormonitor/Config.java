package io.github.marciorodrigues87.trustedadvisormonitor;

public enum Config {
	SLACK_USERNAME("trusted-advisor-monitor"), //
	SLACK_ICON(":money_with_wings:"), // 
	SLACK_HOOK("https://hooks.slack.com/services/YOUR_HOOK"), //
	AWS_CREDENTIAL_NAMES("ACCOUNT_1,ACCOUNT_2"), //
	AWS_CREDENTIALS_IDS("KEY_ID_1,KEY_ID_2"), //
	AWS_CREDENTIALS_SECRETS("KEY_SECRET_1,KEY_SECRET_2"), //
	CHECK_PERIOD_MINUTES("1440"); //

	private final String value;

	private Config(String value) {
		this.value = value;
	}

	public String[] asStringArray() {
		return asString().split(",");
	}
	
	public int asInt() {
		return Integer.parseInt(asString());
	}
	
	public String asString() {
		if (System.getenv(this.name()) != null) {
			return System.getenv(this.name());
		}
		return this.value;
	}
}
