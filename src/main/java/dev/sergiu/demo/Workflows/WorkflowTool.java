package dev.sergiu.demo.Workflows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class WorkflowTool {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTool.class);

    private static final Map<String, String> workflows = new HashMap<String, String>();

    static {
        workflows.put("Policy Violation", "Workflow activated to launch policy violation actions");
        workflows.put("Batch Provisioning", "Workflow activated to launch batch requests.");
        workflows.put("Identity Event", "Workflow activated for identity event. For example, start / end dates for deferred entitlement, role assignment, or role removal.");
        workflows.put("Identity Lifecycle", "Workflow activated for Lifecycle events. For example, Lifecycle Event -- Joiner or Lifecycle Event -- Leaver.");
        workflows.put("Scheduled Assignment", "Workflow activated to when a role is ready to be assigned.");
        workflows.put("LCM Provisioning","Workflow activated for Lifecycle Manager provisioning tasks.");
        workflows.put("LCM Identity", "Workflow associated with Lifecycle Manager Identity related tasks, for example, LCM Create and Update.");
    }

    @McpTool(name = "workflows-overview",
            description = "Returns the list of SailPoint's key Workflow features with a one-line summary for each. "
                    + "Call this when the user asks about SailPoint Type of Workflows can do. Use the optional 'limit' parameter "
                    + "to cap the number of workflows returned.")
    public String getWorkflowsOverview(
            @McpToolParam(description = "Maximum number of workflows to return; returns all when omitted", required = false) Integer limit) {

        int max = (limit == null || limit <=0) ? workflows.size() : Math.min(limit, workflows.size());
             log.info("Tool invoked: Workflows overview");

        String list = workflows.entrySet().stream()
                .limit(max)
                .map(e -> "- " + e.getKey() + ": " + firstSentence(e.getValue()))
                .collect(Collectors.joining("\n"));
        return "SailPoint Workflow Types (" + max + " of " + workflows.size() + "):\n" + list
                + "\n\nUse 'sailpoint-worklow-details' with a feature id for the full description. TO DO SOON";


    }

    private static String firstSentence(String text) {
        int dot = text.indexOf('.');
        return dot > 0 ? text.substring(0, dot + 1) : text;
    }
}
