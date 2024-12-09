package org.embulk.input.jira.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.embulk.config.ConfigException;
import org.embulk.input.jira.Issue;
import org.embulk.input.jira.JiraInputPlugin.PluginTask;
import org.embulk.input.jira.util.JiraException;
import org.embulk.input.jira.util.JiraUtil;
import org.embulk.util.retryhelper.RetryExecutor;
import org.embulk.util.retryhelper.RetryGiveupException;
import org.embulk.util.retryhelper.Retryable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.util.Base64.getEncoder;
import static org.apache.http.HttpHeaders.ACCEPT;
import static org.apache.http.HttpHeaders.AUTHORIZATION;
import static org.apache.http.HttpHeaders.CONTENT_TYPE;
import static org.embulk.input.jira.Constant.HTTP_TIMEOUT;
import static org.embulk.input.jira.Constant.MIN_RESULTS;

public class JiraClient
{
    public JiraClient() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(JiraClient.class);

    public void checkUserCredentials(final PluginTask task)
    {
        try {
            authorizeAndRequest(task, JiraUtil.buildPermissionUrl(task.getUri()), null);
        }
        catch (final JiraException e) {
            LOGGER.error(String.format("JIRA return status (%s), reason (%s)", e.getStatusCode(), e.getMessage()));
            if (e.getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
                throw new ConfigException("Could not authorize with your credential.");
            }
            throw new ConfigException("Could not authorize with your credential due to problems when contacting JIRA API.");
        }
    }

    public List<Issue> searchIssues(final PluginTask task, final int startAt, final int maxResults)
    {
        final String response = searchJiraAPI(task, startAt, maxResults);
        final JsonObject result = new JsonParser().parse(response).getAsJsonObject();
        return StreamSupport.stream(result.get("issues").getAsJsonArray().spliterator(), false)
                            .map(jsonElement -> {
                                final JsonObject json = jsonElement.getAsJsonObject();
                                final JsonObject fields = json.get("fields").getAsJsonObject();
                                final Set<Entry<String, JsonElement>> entries = fields.entrySet();
                                json.remove("fields");
                                // Merged all properties in fields to the object
                                for (final Entry<String, JsonElement> entry : entries) {
                                    json.add(entry.getKey(), entry.getValue());
                                }
                                return new Issue(json);
                            })
                            .collect(Collectors.toList());
    }

    public int getTotalCount(final PluginTask task)
    {
        return new JsonParser().parse(searchJiraAPI(task, 0, MIN_RESULTS)).getAsJsonObject().get("total").getAsInt();
    }

    private String searchJiraAPI(final PluginTask task, final int startAt, final int maxResults)
    {
        try {
            return RetryExecutor.builder()
                    .withRetryLimit(task.getRetryLimit())
                    .withInitialRetryWaitMillis(task.getInitialRetryIntervalMillis())
                    .withMaxRetryWaitMillis(task.getMaximumRetryIntervalMillis())
                    .build()
                    .runInterruptible(new Retryable<String>()
                    {
                @Override
                public String call() throws Exception
                {
                    return authorizeAndRequest(task, JiraUtil.buildSearchUrl(task.getUri()), createSearchIssuesBody(task, startAt, maxResults));
                }

                @Override
                public boolean isRetryableException(final Exception exception)
                {
                    if (exception instanceof JiraException) {
                        final int statusCode = ((JiraException) exception).getStatusCode();
                        // When overloading JIRA APIs (i.e 100 requests per second) the API will return 401 although the credential is correct. So add retry for this
                        // 429 is stand for "Too many requests"
                        // Other 4xx considered errors
                        return statusCode / 100 != 4 || statusCode == HttpStatus.SC_UNAUTHORIZED || statusCode == 429;
                    }
                    return false;
                }

                @Override
                public void onRetry(final Exception exception, final int retryCount, final int retryLimit, final int retryWait)
                        throws RetryGiveupException
                {
                    if (exception instanceof JiraException) {
                        final String message = String
                                .format("Retrying %d/%d after %d seconds. HTTP status code: %s",
                                        retryCount, retryLimit,
                                        retryWait / 1000,
                                        ((JiraException) exception).getStatusCode());
                        LOGGER.warn(message);
                    }
                    else {
                        final String message = String
                                .format("Retrying %d/%d after %d seconds. Message: %s",
                                        retryCount, retryLimit,
                                        retryWait / 1000,
                                        exception.getMessage());
                        LOGGER.warn(message, exception);
                    }
                }

                @Override
                public void onGiveup(final Exception firstException, final Exception lastException) throws RetryGiveupException
                {
                    LOGGER.warn("Retry Limit Exceeded");
                }
            });
        }
        catch (RetryGiveupException | InterruptedException e) {
            if (e instanceof RetryGiveupException && e.getCause() != null && e.getCause() instanceof JiraException) {
                throw new ConfigException(e.getCause().getMessage());
            }
            throw new ConfigException(e);
        }
    }

    private String authorizeAndRequest(final PluginTask task, final String url, final String body) throws JiraException
    {
        try (CloseableHttpClient client = createHttpClient()) {
            HttpRequestBase request;
            if (body == null) {
                request = createGetRequest(task, url);
            }
            else {
                request = createPostRequest(task, url, body);
            }
            try (CloseableHttpResponse response = client.execute(request)) {
             // Check for HTTP response code : 200 : SUCCESS
                final int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != HttpStatus.SC_OK) {
                    throw new JiraException(statusCode, extractErrorMessages(EntityUtils.toString(response.getEntity())));
                }
                return EntityUtils.toString(response.getEntity());
            }
        }
        catch (final IOException e) {
            throw new JiraException(-1, e.getMessage());
        }
    }

    private String extractErrorMessages(final String errorResponse)
    {
        final List<String> messages = new ArrayList<>();
        try {
            final JsonObject errorObject = new JsonParser().parse(errorResponse).getAsJsonObject();
            for (final JsonElement element : errorObject.get("errorMessages").getAsJsonArray()) {
                messages.add(element.getAsString());
            }
        }
        catch (final Exception e) {
            messages.add(errorResponse);
        }
        return String.join(" , ", messages);
    }

    @VisibleForTesting
    public CloseableHttpClient createHttpClient()
    {
        return HttpClientBuilder.create()
                    .setDefaultRequestConfig(RequestConfig.custom()
                                                        .setConnectTimeout(HTTP_TIMEOUT)
                                                        .setConnectionRequestTimeout(HTTP_TIMEOUT)
                                                        .setSocketTimeout(HTTP_TIMEOUT)
                                                        .setCookieSpec(CookieSpecs.STANDARD)
                                                        .build())
                    .build();
    }

    private HttpRequestBase createPostRequest(final PluginTask task, final String url, final String body) throws IOException
    {
        final HttpPost request = new HttpPost(url);
        switch (task.getAuthMethod()) {
        default:
            request.setHeader(
                    AUTHORIZATION,
                    String.format("Basic %s",
                                getEncoder().encodeToString(String.format("%s:%s",
                                task.getUsername(),
                                task.getPassword()).getBytes())));
            request.setHeader(ACCEPT, "application/json");
            request.setHeader(CONTENT_TYPE, "application/json");
            break;
        }
        request.setEntity(new StringEntity(body));
        return request;
    }

    private HttpRequestBase createGetRequest(final PluginTask task, final String url)
    {
        final HttpGet request = new HttpGet(url);
        switch (task.getAuthMethod()) {
        default:
            request.setHeader(
                    AUTHORIZATION,
                    String.format("Basic %s",
                                getEncoder().encodeToString(String.format("%s:%s",
                                task.getUsername(),
                                task.getPassword()).getBytes())));
            request.setHeader(ACCEPT, "application/json");
            request.setHeader(CONTENT_TYPE, "application/json");
            break;
        }
        return request;
    }

    private String createSearchIssuesBody(final PluginTask task, final int startAt, final int maxResults)
    {
        final JsonObject body = new JsonObject();
        final Optional<String> jql = task.getJQL();
        body.add("jql", new JsonPrimitive(jql.orElse("")));
        body.add("startAt", new JsonPrimitive(startAt));
        body.add("maxResults", new JsonPrimitive(maxResults));
        final JsonArray fields = new JsonArray();
        fields.add("*all");
        body.add("fields", fields);
        final JsonArray expand = new JsonArray();
        task.getExpand().forEach(e -> {
            expand.add(e);
        });
        body.add("expand", expand);
        return body.toString();
    }
}
