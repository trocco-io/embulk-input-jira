package org.embulk.input.jira;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.embulk.EmbulkTestRuntime;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.input.jira.JiraInputPlugin.PluginTask;
import org.embulk.input.jira.client.JiraClient;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.Schema;
import org.embulk.spi.TestPageBuilderReader.MockPageOutput;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.embulk.input.jira.JiraInputPlugin.CONFIG_MAPPER;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JiraInputPluginTest
{
    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();

    private JiraInputPlugin plugin;
    private JiraClient jiraClient;
    private JsonObject data;
    private ConfigSource config;
    private final CloseableHttpClient client = Mockito.mock(CloseableHttpClient.class);
    private final CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
    private final StatusLine statusLine = Mockito.mock(StatusLine.class);

    private final MockPageOutput output = new MockPageOutput();
    private PageBuilder pageBuilder;

    @Before
    public void setUp() throws IOException
    {
        if (plugin == null) {
            plugin = Mockito.spy(new JiraInputPlugin());
            jiraClient = Mockito.spy(new JiraClient());
            data = TestHelpers.getJsonFromFile("jira_input_plugin.json");
            config = TestHelpers.config();
            CONFIG_MAPPER.map(config, PluginTask.class);
            pageBuilder = Mockito.mock(PageBuilder.class);
        }
        when(plugin.getJiraClient()).thenReturn(jiraClient);
        when(jiraClient.createHttpClient()).thenReturn(client);
        when(client.execute(Mockito.any(HttpUriRequest.class))).thenReturn(response);
        when(response.getStatusLine()).thenReturn(statusLine);
        doReturn(pageBuilder).when(plugin).getPageBuilder(Mockito.any(), Mockito.any());
    }

    @Test
    public void test_run_withEmptyResult() throws IOException
    {
        final JsonObject authorizeResponse = data.get("authenticateSuccess").getAsJsonObject();
        final JsonObject searchResponse = data.get("emptyResult").getAsJsonObject();

        when(statusLine.getStatusCode())
                .thenReturn(authorizeResponse.get("statusCode").getAsInt())
                .thenReturn(searchResponse.get("statusCode").getAsInt());
        when(response.getEntity())
                .thenReturn(new StringEntity(authorizeResponse.get("body").toString()))
                .thenReturn(new StringEntity(searchResponse.get("body").toString()));

        plugin.transaction(config, new Control());
        // Check credential 1 + getTotal 1 + loadData 0
        verify(jiraClient, times(2)).createHttpClient();
        verify(pageBuilder, times(0)).addRecord();
        verify(pageBuilder, times(1)).finish();
    }

    public void test_runDynamicSchema_withEmptyResult() throws IOException
    {
        final JsonObject searchResponse = data.get("emptyResult").getAsJsonObject();

        when(statusLine.getStatusCode())
                .thenReturn(searchResponse.get("statusCode").getAsInt());
        when(response.getEntity())
                .thenReturn(new StringEntity(searchResponse.get("body").toString()));
        plugin.transaction(TestHelpers.dynamicSchemaConfig(), new Control());
        verify(pageBuilder, times(0)).addRecord();
    }

    @Test
    public void test_runDynamicSchema_withResult() throws IOException
    {
        final JsonObject authorizeResponse = data.get("authenticateSuccess").getAsJsonObject();
        final JsonObject searchResponse = data.get("oneRecordResult").getAsJsonObject();

        when(statusLine.getStatusCode())
                .thenReturn(searchResponse.get("statusCode").getAsInt())
                .thenReturn(authorizeResponse.get("statusCode").getAsInt())
                .thenReturn(searchResponse.get("statusCode").getAsInt());
        when(response.getEntity())
                .thenReturn(new StringEntity(searchResponse.get("body").toString()))
                .thenReturn(new StringEntity(authorizeResponse.get("body").toString()))
                .thenReturn(new StringEntity(searchResponse.get("body").toString()));

        plugin.transaction(TestHelpers.dynamicSchemaConfig(), new Control());
        // Check credential 1 + getTotal 1 + loadData 2
        verify(jiraClient, times(4)).createHttpClient();
        verify(pageBuilder, times(1)).addRecord();
        verify(pageBuilder, times(1)).finish();
    }

    @Test
    public void test_run_with1RecordsResult() throws IOException
    {
        final JsonObject authorizeResponse = data.get("authenticateSuccess").getAsJsonObject();
        final JsonObject searchResponse = data.get("oneRecordResult").getAsJsonObject();

        when(statusLine.getStatusCode())
                .thenReturn(authorizeResponse.get("statusCode").getAsInt())
                .thenReturn(searchResponse.get("statusCode").getAsInt());
        when(response.getEntity())
                .thenReturn(new StringEntity(authorizeResponse.get("body").toString()))
                .thenReturn(new StringEntity(searchResponse.get("body").toString()));

        plugin.transaction(config, new Control());
        // Check credential 1 + getTotal 1 + loadData 1
        verify(jiraClient, times(3)).createHttpClient();
        verify(pageBuilder, times(1)).addRecord();
        verify(pageBuilder, times(1)).finish();
    }

    @Test
    public void test_run_with2PagesResult() throws IOException
    {
        final JsonObject authorizeResponse = data.get("authenticateSuccess").getAsJsonObject();
        final JsonObject searchResponse = data.get("2PagesResult").getAsJsonObject();

        when(statusLine.getStatusCode())
                .thenReturn(authorizeResponse.get("statusCode").getAsInt())
                .thenReturn(searchResponse.get("statusCode").getAsInt());
        when(response.getEntity())
                .thenReturn(new StringEntity(authorizeResponse.get("body").toString()))
                .thenReturn(new StringEntity(searchResponse.get("body").toString()));

        plugin.transaction(config, new Control());
        // Check credential 1 + getTotal 1 + loadData 2
        verify(jiraClient, times(4)).createHttpClient();
        verify(pageBuilder, times(2)).addRecord();
        verify(pageBuilder, times(1)).finish();
    }

    @Test
    public void test_preview_withEmptyResult() throws IOException
    {
        when(plugin.isPreview()).thenReturn(true);
        final JsonObject authorizeResponse = data.get("authenticateSuccess").getAsJsonObject();
        final JsonObject searchResponse = data.get("emptyResult").getAsJsonObject();

        when(statusLine.getStatusCode())
                .thenReturn(authorizeResponse.get("statusCode").getAsInt())
                .thenReturn(searchResponse.get("statusCode").getAsInt());
        when(response.getEntity())
                .thenReturn(new StringEntity(searchResponse.get("body").toString()));

        plugin.transaction(config, new Control());
        // Check credential 1 + loadData 1
        verify(jiraClient, times(2)).createHttpClient();
        verify(pageBuilder, times(0)).addRecord();
        verify(pageBuilder, times(1)).finish();
    }

    @Test
    public void test_preview_with1RecordsResult() throws IOException
    {
        when(plugin.isPreview()).thenReturn(true);
        final JsonObject authorizeResponse = data.get("authenticateSuccess").getAsJsonObject();
        final JsonObject searchResponse = data.get("oneRecordResult").getAsJsonObject();

        when(statusLine.getStatusCode())
                .thenReturn(authorizeResponse.get("statusCode").getAsInt())
                .thenReturn(searchResponse.get("statusCode").getAsInt());
        when(response.getEntity())
                .thenReturn(new StringEntity(authorizeResponse.get("body").toString()))
                .thenReturn(new StringEntity(searchResponse.get("body").toString()));

        plugin.transaction(config, new Control());
        // Check credential 1 + loadData 1
        verify(jiraClient, times(2)).createHttpClient();
        verify(pageBuilder, times(1)).addRecord();
        verify(pageBuilder, times(1)).finish();
    }

    @Test
    public void test_guess() throws IOException
    {
        final ConfigSource configSource = TestHelpers.config();

        final JsonObject authorizeResponse = data.get("authenticateSuccess").getAsJsonObject();
        final JsonObject searchResponse = data.get("guessDataResult").getAsJsonObject();

        when(statusLine.getStatusCode())
                .thenReturn(authorizeResponse.get("statusCode").getAsInt())
                .thenReturn(searchResponse.get("statusCode").getAsInt());
        when(response.getEntity())
                .thenReturn(new StringEntity(searchResponse.get("body").toString()))
                .thenReturn(new StringEntity(searchResponse.get("body").toString()));

        final ConfigDiff result = plugin.guess(configSource);
        final JsonElement expected = data.get("guessResult").getAsJsonObject();
        final JsonElement actual = new JsonParser().parse(result.toString());
        assertEquals(expected, actual);
    }

    private class Control implements InputPlugin.Control
    {
        @Override
        public List<TaskReport> run(final TaskSource taskSource, final Schema schema, final int taskCount)
        {
            final List<TaskReport> reports = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                reports.add(plugin.run(taskSource, schema, i, output));
            }
            return reports;
        }
    }
}
