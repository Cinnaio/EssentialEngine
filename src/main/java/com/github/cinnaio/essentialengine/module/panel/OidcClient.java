package com.github.cinnaio.essentialengine.module.panel;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面板的 OpenID Connect 客户端（RP）。
 *
 * <p>走标准的授权码流程 + PKCE，用来对接 EnderPass 这类 OIDC Provider，
 * 让服主用皮肤站账号登录面板，而不必再单独记一个面板密码。</p>
 *
 * <p>整个实现只用 JDK 自带的 {@link HttpClient} 与 {@code java.security}，
 * 不引入任何 JWT / OAuth 库——插件是要塞进服务端 jar 的，能少一个依赖是一个。</p>
 *
 * <p><b>安全要点</b>：</p>
 * <ul>
 *   <li>{@code state} 防 CSRF、{@code nonce} 防 id_token 重放，都是一次性的，5 分钟过期；</li>
 *   <li>PKCE 用 S256，即使授权码在重定向途中被截获也换不出 token；</li>
 *   <li>id_token 的 RS256 签名对 JWKS 公钥验签，并逐项核对 iss / aud / exp / nonce。
 *       规范允许在直连 token 端点时跳过验签，但这里坚持验——服主完全可能把
 *       issuer 配成内网 http 地址，那时候就没有 TLS 可依赖了。</li>
 * </ul>
 */
public class OidcClient {

    private static final Gson GSON = new Gson();
    /** 允许的时钟偏差。 */
    private static final long CLOCK_SKEW_MS = 60_000L;
    /** 授权请求的有效期。 */
    private static final long FLOW_TTL_MS = 5 * 60_000L;

    /** 一次进行中的授权请求。 */
    private record Flow(String nonce, String verifier, long expiry) {
    }

    /** 登录成功后拿到的身份。 */
    public record Identity(String subject, String username, String minecraftUuid,
                           boolean admin, int permission) {
    }

    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String scopes;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Flow> flows = new ConcurrentHashMap<>();

    private volatile JsonObject discovery;
    private volatile Map<String, RSAPublicKey> jwks = Map.of();
    private volatile long jwksFetchedAt;

    public OidcClient(String issuer, String clientId, String clientSecret,
                      String redirectUri, String scopes) {
        this.issuer = trimSlash(issuer);
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        String requested = scopes == null || scopes.isBlank() ? "openid profile" : scopes.trim();
        // provider 明确要求必须带 openid，否则不会下发 id_token
        this.scopes = requested.contains("openid") ? requested : "openid " + requested;
    }

    private static String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public boolean isConfigured() {
        return !issuer.isEmpty() && !clientId.isEmpty()
                && !clientSecret.isEmpty() && !redirectUri.isEmpty();
    }

    public String redirectUri() {
        return redirectUri;
    }

    // ------------------------------------------------------------------ 发现文档

    /** 拉取并缓存发现文档。第一次调用才会发请求，provider 挂掉不会拖累插件启动。 */
    private JsonObject discovery() throws Exception {
        JsonObject cached = discovery;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (discovery != null) {
                return discovery;
            }
            String url = issuer.endsWith("/.well-known/openid-configuration")
                    ? issuer : issuer + "/.well-known/openid-configuration";
            JsonObject json = getJson(url);
            if (!json.has("authorization_endpoint") || !json.has("token_endpoint")) {
                throw new IllegalStateException("发现文档缺少必要端点，请检查 issuer 是否正确: " + url);
            }
            discovery = json;
            return json;
        }
    }

    private String endpoint(String key) throws Exception {
        JsonElement value = discovery().get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    // ------------------------------------------------------------------ 第一步：跳转授权

    /**
     * 生成授权 URL，同时把 state / nonce / PKCE 校验值记在服务端。
     *
     * @return 浏览器应该跳转过去的地址
     */
    public String authorizationUrl() throws Exception {
        purgeExpiredFlows();

        String state = randomToken();
        String nonce = randomToken();
        String verifier = randomToken() + randomToken();
        flows.put(state, new Flow(nonce, verifier, System.currentTimeMillis() + FLOW_TTL_MS));

        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        StringBuilder url = new StringBuilder(endpoint("authorization_endpoint"));
        url.append(url.indexOf("?") >= 0 ? '&' : '?');
        url.append("response_type=code")
                .append("&client_id=").append(encode(clientId))
                .append("&redirect_uri=").append(encode(redirectUri))
                .append("&scope=").append(encode(scopes))
                .append("&state=").append(encode(state))
                .append("&nonce=").append(encode(nonce))
                .append("&code_challenge=").append(encode(challenge))
                .append("&code_challenge_method=S256");
        return url.toString();
    }

    // ------------------------------------------------------------------ 第二步：回调换取身份

    /**
     * 用授权码换 token 并校验 id_token，返回登录者身份。
     *
     * @throws IllegalStateException 任何一步校验没过都抛这个，消息可直接展示给用户
     */
    public Identity exchange(String code, String state) throws Exception {
        if (code == null || code.isEmpty()) {
            throw new IllegalStateException("授权服务器没有返回授权码");
        }
        // state 一次性消费：既防 CSRF，也防同一个回调被重放
        Flow flow = state == null ? null : flows.remove(state);
        if (flow == null || System.currentTimeMillis() > flow.expiry()) {
            throw new IllegalStateException("登录请求已过期或无效，请重新发起登录");
        }

        String body = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri)
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&code_verifier=" + encode(flow.verifier());

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint("token_endpoint")))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("换取令牌失败（HTTP " + response.statusCode() + "）");
        }
        JsonObject token = GSON.fromJson(response.body(), JsonObject.class);
        if (token == null || !token.has("id_token")) {
            throw new IllegalStateException("响应里没有 id_token，请确认客户端申请了 openid scope");
        }
        JsonObject claims = verifyIdToken(token.get("id_token").getAsString(), flow.nonce());
        return toIdentity(claims);
    }

    private Identity toIdentity(JsonObject claims) {
        String subject = str(claims, "sub");
        String username = str(claims, "minecraft_player_name");
        if (username.isEmpty()) {
            username = str(claims, "preferred_username");
        }
        if (username.isEmpty()) {
            username = str(claims, "nickname");
        }
        int permission = claims.has("permission") && claims.get("permission").isJsonPrimitive()
                ? claims.get("permission").getAsInt() : 0;
        boolean admin = claims.has("is_admin") && claims.get("is_admin").isJsonPrimitive()
                && claims.get("is_admin").getAsBoolean();
        return new Identity(subject, username, str(claims, "minecraft_uuid"), admin, permission);
    }

    // ------------------------------------------------------------------ id_token 校验

    /** 验签并逐项核对声明，返回 payload。 */
    private JsonObject verifyIdToken(String jwt, String expectedNonce) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("id_token 格式不正确");
        }
        JsonObject header = GSON.fromJson(decodeToString(parts[0]), JsonObject.class);
        JsonObject payload = GSON.fromJson(decodeToString(parts[1]), JsonObject.class);
        if (header == null || payload == null) {
            throw new IllegalStateException("id_token 解析失败");
        }

        String algorithm = str(header, "alg");
        if (!"RS256".equalsIgnoreCase(algorithm)) {
            // 明确拒绝 none 与对称算法，避免算法混淆攻击
            throw new IllegalStateException("不支持的 id_token 签名算法: " + algorithm);
        }
        RSAPublicKey key = resolveKey(str(header, "kid"));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
            throw new IllegalStateException("id_token 签名校验失败");
        }

        String tokenIssuer = trimSlash(str(payload, "iss"));
        if (!tokenIssuer.equals(trimSlash(str(discovery(), "issuer")))) {
            throw new IllegalStateException("id_token 的签发者与发现文档不一致");
        }
        if (!audienceMatches(payload)) {
            throw new IllegalStateException("id_token 不是签发给本客户端的");
        }
        long now = System.currentTimeMillis();
        long expiry = payload.has("exp") ? payload.get("exp").getAsLong() * 1000L : 0L;
        if (expiry <= 0 || now > expiry + CLOCK_SKEW_MS) {
            throw new IllegalStateException("id_token 已过期");
        }
        if (expectedNonce != null && !expectedNonce.equals(str(payload, "nonce"))) {
            throw new IllegalStateException("nonce 不匹配，登录请求可能被重放");
        }
        return payload;
    }

    private boolean audienceMatches(JsonObject payload) {
        JsonElement audience = payload.get("aud");
        if (audience == null || audience.isJsonNull()) {
            return false;
        }
        if (audience.isJsonArray()) {
            JsonArray array = audience.getAsJsonArray();
            for (JsonElement item : array) {
                if (clientId.equals(item.getAsString())) {
                    return true;
                }
            }
            return false;
        }
        return clientId.equals(audience.getAsString());
    }

    /** 按 kid 取公钥；取不到就刷新一次 JWKS（provider 轮换密钥后能自愈）。 */
    private RSAPublicKey resolveKey(String kid) throws Exception {
        RSAPublicKey key = lookupKey(kid);
        if (key != null) {
            return key;
        }
        refreshJwks();
        key = lookupKey(kid);
        if (key == null) {
            throw new IllegalStateException("JWKS 里找不到匹配的验签公钥");
        }
        return key;
    }

    private RSAPublicKey lookupKey(String kid) {
        Map<String, RSAPublicKey> current = jwks;
        if (current.isEmpty()) {
            return null;
        }
        if (kid != null && !kid.isEmpty()) {
            return current.get(kid);
        }
        // 没给 kid 且只有一把钥匙时直接用它
        return current.size() == 1 ? current.values().iterator().next() : null;
    }

    private synchronized void refreshJwks() throws Exception {
        // 防止验签失败时被反复触发去打 provider
        if (System.currentTimeMillis() - jwksFetchedAt < 60_000L && !jwks.isEmpty()) {
            return;
        }
        String uri = endpoint("jwks_uri");
        if (uri == null) {
            throw new IllegalStateException("发现文档里没有 jwks_uri");
        }
        JsonObject json = getJson(uri);
        Map<String, RSAPublicKey> keys = new ConcurrentHashMap<>();
        JsonArray array = json.getAsJsonArray("keys");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject jwk = element.getAsJsonObject();
                if (!"RSA".equals(str(jwk, "kty"))) {
                    continue;
                }
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(str(jwk, "n")));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(str(jwk, "e")));
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                keys.put(str(jwk, "kid"), key);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("JWKS 里没有可用的 RSA 公钥");
        }
        jwks = keys;
        jwksFetchedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ 工具

    private JsonObject getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("请求 " + url + " 失败（HTTP " + response.statusCode() + "）");
        }
        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        if (json == null) {
            throw new IllegalStateException("请求 " + url + " 返回的不是 JSON");
        }
        return json;
    }

    private void purgeExpiredFlows() {
        long now = System.currentTimeMillis();
        flows.values().removeIf(flow -> now > flow.expiry());
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static String base64Url(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    private static String decodeToString(String segment) {
        return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /** 判断某个身份是否被允许进入面板。 */
    public static boolean isAllowed(Identity identity, boolean requireAdmin, List<String> allowlist) {
        if (identity == null) {
            return false;
        }
        // 皮肤站已封禁的账号一律拒绝，不受名单影响
        if (identity.permission() < 0) {
            return false;
        }
        if (allowlist != null && !allowlist.isEmpty()) {
            for (String entry : allowlist) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String value = entry.trim();
                if (value.equalsIgnoreCase(identity.username())
                        || value.equalsIgnoreCase(identity.minecraftUuid())
                        || value.equals(identity.subject())) {
                    return true;
                }
            }
            return false;
        }
        return !requireAdmin || identity.admin();
    }

    /** 记进日志的可读身份。没有角色名时退回 sub，不输出邮箱之类的信息。 */
    public static String describe(Identity identity) {
        if (identity == null) {
            return "unknown";
        }
        return identity.username() == null || identity.username().isEmpty()
                ? "sub:" + identity.subject() : identity.username();
    }
}
