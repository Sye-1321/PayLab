package io.github.sye1321.paylab.run;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookUrlValidator {

    private final Set<String> allowedHosts;

    public WebhookUrlValidator(@Value("${paylab.webhook.allowed-hosts:localhost,127.0.0.1,::1}") String hosts) {
        this.allowedHosts = Arrays.stream(hosts.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public String validate(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid webhook URL", invalid);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || host == null || uri.getRawUserInfo() != null
                || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Webhook URL is not an allowed HTTP target");
        }
        return uri.toString();
    }
}
