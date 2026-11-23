package ai.whitekey.policy;

import ai.whitekey.api.dto.RuleEffect;
import ai.whitekey.config.PolicyProperties;
import ai.whitekey.entity.HttpActionEntity;
import ai.whitekey.entity.HttpRuleEntity;
import ai.whitekey.proxy.ProxyTarget;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

@Component
public final class PolicyDecisionEngine {
    public static final String NO_MATCHING_RULE_REASON = "No applicable rule allows this action";
    public static final String NO_MATCHING_RULE_ALLOW_REASON = "No matching rule; allowed by default policy";
    public static final String NO_MATCHING_RULE_APPROVAL_REASON = "No matching rule; requires human approval by default policy";
    public static final String RULE_DENIED_REASON = "A matching rule explicitly denies this action";
    public static final String RULE_REQUIRES_APPROVAL_REASON = "A matching rule requires human approval";
    public static final String RULE_ALLOWED_REASON = "Matching rules allow this action";
    public static final String INVALID_BODY_REASON = "Request body is not valid JSON or contains duplicate keys";

    private final CelBodyEvaluator bodyEvaluator;
    private final RuleEffect defaultEffect;

    public PolicyDecisionEngine(CelBodyEvaluator bodyEvaluator, PolicyProperties policyProperties) {
        this.bodyEvaluator = bodyEvaluator;
        this.defaultEffect = policyProperties != null ? policyProperties.resolvedDefaultEffect() : RuleEffect.DENY;
    }

    public record Decision(RuleEffect effect, String reason, boolean unmatched) {
        public Decision(RuleEffect effect, String reason) {
            this(effect, reason, false);
        }

        public boolean isUnmatchedAction() {
            return unmatched;
        }
    }

    public record HeaderEvaluation(boolean needsBody, Decision decision) {}

    private record RequestMetadata(
            HttpMethod method,
            String host,
            Integer port,
            String path,
            Map<String, List<String>> queryParams,
            HttpHeaders headers) {

        static RequestMetadata from(HttpMethod method, URI url, HttpHeaders headers) {
            if (method == null || url == null || url.getHost() == null) {
                return null;
            }
            ProxyTarget target = ProxyTarget.fromUri(url);
            return new RequestMetadata(
                    method,
                    target.host(),
                    target.port(),
                    target.path(),
                    HttpPolicyValidator.parseQueryParams(target.rawQuery()),
                    headers);
        }

        static RequestMetadata from(HttpActionEntity action) {
            if (action == null) {
                return null;
            }
            return new RequestMetadata(
                    action.getMethod(),
                    action.getHost(),
                    action.getPort(),
                    action.getPath(),
                    action.getQueryParams(),
                    action.getHeaders());
        }
    }

    private record HeaderMatchResult(List<HttpRuleEntity> matched, Decision invalidRuleError) {}

    public HeaderEvaluation evaluateHeaders(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            List<HttpRuleEntity> rules) {
        HeaderMatchResult headerMatch = collectHeaderMatches(RequestMetadata.from(method, url, headers), rules);
        if (headerMatch.invalidRuleError() != null) {
            return new HeaderEvaluation(false, headerMatch.invalidRuleError());
        }

        List<HttpRuleEntity> headerMatched = headerMatch.matched();
        if (headerMatched.stream().anyMatch(r -> r.getEffect() == RuleEffect.DENY && !hasBodyCondition(r))) {
            return new HeaderEvaluation(false, new Decision(RuleEffect.DENY, RULE_DENIED_REASON));
        }
        if (headerMatched.stream().anyMatch(PolicyDecisionEngine::hasBodyCondition)) {
            return new HeaderEvaluation(true, null);
        }
        return new HeaderEvaluation(false, resolveDecision(headerMatched));
    }

    public Decision evaluateHttp(HttpActionEntity action, List<HttpRuleEntity> rules) {
        HeaderMatchResult headerMatch = collectHeaderMatches(RequestMetadata.from(action), rules);
        if (headerMatch.invalidRuleError() != null) {
            return headerMatch.invalidRuleError();
        }

        List<HttpRuleEntity> headerMatched = headerMatch.matched();
        if (action != null && action.isBodyTruncated()) {
            List<HttpRuleEntity> nonBodyRules = headerMatched.stream()
                    .filter(rule -> !hasBodyCondition(rule))
                    .toList();
            return resolveDecision(nonBodyRules);
        }

        return evaluateBodyRules(
                headerMatched,
                () -> action == null || bodyEvaluator.isValidJson(action.getBody()),
                rule -> bodyEvaluator.matches(rule.getBodyCondition(), action != null ? action.getBody() : null));
    }

    public Decision evaluateWithBodyFile(
            HttpMethod method,
            URI url,
            HttpHeaders headers,
            Path bodyFile,
            List<HttpRuleEntity> rules) {
        HeaderMatchResult headerMatch = collectHeaderMatches(RequestMetadata.from(method, url, headers), rules);
        if (headerMatch.invalidRuleError() != null) {
            return headerMatch.invalidRuleError();
        }

        return evaluateBodyRules(
                headerMatch.matched(),
                () -> bodyEvaluator.isValidJson(bodyFile),
                rule -> bodyEvaluator.matches(rule.getBodyCondition(), bodyFile));
    }

    private static HeaderMatchResult collectHeaderMatches(RequestMetadata req, List<HttpRuleEntity> rules) {
        List<HttpRuleEntity> matched = new ArrayList<>();
        for (HttpRuleEntity rule : rules) {
            if (rule.getEffect() == null) {
                return new HeaderMatchResult(
                        List.of(),
                        new Decision(
                                RuleEffect.DENY,
                                "Invalid applicable rule " + rule.getId() + ": " + RuleEffect.ERROR_EFFECT_REQUIRED));
            }
            if (req != null && matchesRequestMetadata(req, rule)) {
                matched.add(rule);
            }
        }
        return new HeaderMatchResult(matched, null);
    }

    private Decision evaluateBodyRules(
            List<HttpRuleEntity> headerMatched,
            BooleanSupplier validJson,
            Predicate<HttpRuleEntity> bodyMatcher) {
        boolean hasMatchingBodyRule = headerMatched.stream().anyMatch(PolicyDecisionEngine::hasBodyCondition);
        if (hasMatchingBodyRule && !validJson.getAsBoolean()) {
            return new Decision(RuleEffect.DENY, INVALID_BODY_REASON);
        }
        List<HttpRuleEntity> matched = headerMatched.stream()
                .filter(rule -> !hasBodyCondition(rule) || bodyMatcher.test(rule))
                .toList();
        return resolveDecision(matched);
    }

    private Decision resolveDecision(List<HttpRuleEntity> matched) {
        if (matched.stream().anyMatch(rule -> rule.getEffect() == RuleEffect.DENY)) {
            return new Decision(RuleEffect.DENY, RULE_DENIED_REASON);
        }
        if (matched.stream().anyMatch(rule -> rule.getEffect() == RuleEffect.ASK)) {
            return new Decision(RuleEffect.ASK, RULE_REQUIRES_APPROVAL_REASON);
        }
        if (!matched.isEmpty()) {
            return new Decision(RuleEffect.ALLOW, RULE_ALLOWED_REASON);
        }
        return new Decision(defaultEffect, unmatchedReason(defaultEffect), true);
    }

    private static String unmatchedReason(RuleEffect effect) {
        return switch (effect) {
            case ALLOW -> NO_MATCHING_RULE_ALLOW_REASON;
            case ASK -> NO_MATCHING_RULE_APPROVAL_REASON;
            case DENY -> NO_MATCHING_RULE_REASON;
        };
    }

    private static boolean hasBodyCondition(HttpRuleEntity rule) {
        return rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();
    }

    private static boolean matchesRequestMetadata(RequestMetadata req, HttpRuleEntity rule) {
        if (rule.getMethods() != null && !rule.getMethods().isEmpty() && !rule.getMethods().contains(req.method())) {
            return false;
        }
        if (rule.getHosts() != null && !rule.getHosts().isEmpty() && !rule.getHosts().contains(req.host())) {
            return false;
        }
        if (rule.getPorts() != null && !rule.getPorts().isEmpty() && !rule.getPorts().contains(req.port())) {
            return false;
        }
        if (rule.getPaths() != null && !rule.getPaths().isEmpty()
                && rule.getPaths().stream().noneMatch(pattern -> matchesPath(pattern, req.path()))) {
            return false;
        }
        return matchesQueryParams(rule.getQueryParams(), req.queryParams(), rule.getEffect())
                && matchesRuleHeaders(rule.getHeaders(), req.headers(), rule.getEffect());
    }

    private static boolean matchesQueryParams(
            Map<String, List<String>> expected, Map<String, List<String>> actual, RuleEffect effect) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        return expected.entrySet().stream()
                .allMatch(e -> matchesValues(e.getValue(), actual.get(e.getKey()), effect));
    }

    private static boolean matchesRuleHeaders(
            HttpHeaders expected, HttpHeaders actual, RuleEffect effect) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        return expected.headerNames().stream()
                .allMatch(name -> matchesValues(expected.get(name), actual.get(name), effect));
    }

    private static boolean matchesValues(
            List<String> expectedValues, List<String> actualValues, RuleEffect effect) {
        if (actualValues == null) {
            return false;
        }
        if (expectedValues == null || expectedValues.isEmpty()) {
            return true;
        }
        if (effect == RuleEffect.ALLOW) {
            return !actualValues.isEmpty() && actualValues.stream().allMatch(expectedValues::contains);
        }
        return actualValues.stream().anyMatch(expectedValues::contains);
    }

    private static boolean matchesPath(String pattern, String path) {
        if (pattern.endsWith("/*")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(path);
    }
}
