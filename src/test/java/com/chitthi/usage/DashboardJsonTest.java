package com.chitthi.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the Grafana dashboard checked into the repo (not deployed anywhere
 * in this test) is well-formed and actually queries the metrics
 * {@link UsageRecorderTest} proves the app creates - a typo'd metric name
 * here would otherwise only ever surface as a silently empty panel in a
 * real Grafana instance.
 */
class DashboardJsonTest {

    private static final List<String> EXPECTED_METRICS = List.of(
            "chitthi_sarvam_calls_total",
            "chitthi_sarvam_latency_seconds",
            "chitthi_sarvam_cost_inr_total",
            "chitthi_sarvam_units_total",
            "http_server_requests_seconds",
            "chitthi_outbox_unpublished");

    @Test
    void theDashboardParsesAndHasSixPanels() throws Exception {
        JsonNode dashboard = readDashboard();

        assertThat(dashboard.get("title").asText()).isEqualTo("Chitthi");
        assertThat(dashboard.get("panels")).hasSize(6);
    }

    @Test
    void everyPanelHasATitleAndAtLeastOneNonBlankQuery() throws Exception {
        JsonNode dashboard = readDashboard();

        for (JsonNode panel : dashboard.get("panels")) {
            assertThat(panel.get("title").asText()).isNotBlank();
            JsonNode targets = panel.get("targets");
            assertThat(targets).isNotEmpty();
            for (JsonNode target : targets) {
                assertThat(target.get("expr").asText()).isNotBlank();
            }
        }
    }

    @Test
    void everyExpectedMetricIsQueriedByAtLeastOnePanel() throws Exception {
        String dashboardJson = java.nio.file.Files.readString(dashboardFile().toPath());

        for (String metric : EXPECTED_METRICS) {
            assertThat(dashboardJson).as("dashboard should query %s", metric).contains(metric);
        }
    }

    private JsonNode readDashboard() throws Exception {
        return new ObjectMapper().readTree(dashboardFile());
    }

    private File dashboardFile() {
        File file = new File("infra/grafana/dashboards/chitthi.json");
        assertThat(file).exists();
        return file;
    }
}
