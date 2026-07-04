package dev.sergiu.demo.SailPointComponent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class SailPointTool {

    private static final Logger log = LoggerFactory.getLogger(SailPointTool.class);

    private static final Map<String, String> FEATURES = new LinkedHashMap<>();

    static {
        FEATURES.put("identity-lifecycle-management", """
                Identity Lifecycle Management: automates joiner-mover-leaver (JML) processes. When an employee \
                is hired, changes role, or leaves, SailPoint automatically provisions or deprovisions accounts \
                and entitlements across all connected systems, driven by identity attributes from an \
                authoritative source (usually an HR system such as Workday or SAP SuccessFactors).""");
        FEATURES.put("access-certifications", """
                Access Certifications: periodic access review campaigns where managers, application owners, or \
                role owners certify or revoke user access. Supports scheduled, event-driven, and targeted \
                campaigns, with escalations and automatic revocation of rejected access. Key for compliance \
                with SOX, GDPR, HIPAA, and ISO 27001.""");
        FEATURES.put("access-requests", """
                Access Requests: a self-service portal where users request access to applications, roles, or \
                entitlements for themselves or others. Requests flow through configurable multi-level approval \
                workflows, with policy checks (for example SoD violations) evaluated before provisioning.""");
        FEATURES.put("role-management", """
                Role Management: role mining, modeling, and lifecycle management for role-based access control \
                (RBAC). Discovers candidate roles from existing entitlement data, supports business and IT \
                roles, and automates entitlement assignment through role membership criteria.""");
        FEATURES.put("separation-of-duties", """
                Policy Management & Separation of Duties (SoD): defines and enforces policies that detect toxic \
                access combinations (for example, the same user being able to both create and approve \
                payments). Violations can block provisioning, trigger remediation workflows, or be tracked as \
                exceptions with mitigating controls.""");
        FEATURES.put("connectors", """
                Connectors & Integrations: hundreds of out-of-the-box connectors for aggregating accounts and \
                provisioning access across directories, SaaS applications, ERP systems, databases, and cloud \
                platforms. Custom integrations are possible via REST/SCIM connectors and the SailPoint API.""");
        FEATURES.put("ai-identity-security", """
                AI-Driven Identity Security: machine learning for access recommendations, peer-group analysis, \
                outlier detection, and identification of risky, unused, or over-provisioned access. Powers \
                access insights, certification recommendations, and role insights.""");
        FEATURES.put("password-management", """
                Password Management: self-service password reset, password synchronization across connected \
                systems, and enforcement of password policies, reducing helpdesk load.""");
        FEATURES.put("ciem", """
                Cloud Infrastructure Entitlement Management (CIEM): visibility and governance over effective \
                entitlements in AWS, Azure, and GCP - detecting unused permissions and privilege escalation \
                paths, and enforcing least privilege in cloud infrastructure.""");
        FEATURES.put("non-employee-risk-management", """
                Non-Employee Risk Management: lifecycle governance for third-party identities (contractors, \
                vendors, partners) that do not originate in HR systems, including sponsorship, time-bound \
                access, and periodic revalidation.""");
    }

    //tools
    @McpTool(name = "sailpoint-overview",
            description = "Returns a high-level overview of SailPoint: what the company does and its main "
                    + "products (Identity Security Cloud and IdentityIQ). Call this for general questions "
                    + "about what SailPoint is.")
    public String getSailPointOverview() {
        log.info("Tool invoked: sailpoint-overview");
        return """
                SailPoint is a leader in identity security and identity governance and administration (IGA).
                It helps organizations manage and secure digital identities: who has access to what, whether \
                that access is appropriate, and how it is granted, reviewed, and removed.

                Main products:
                - SailPoint Identity Security Cloud (formerly IdentityNow): SaaS-based identity governance \
                platform, delivered in suites (Standard, Business, Business Plus).
                - SailPoint IdentityIQ: on-premises identity governance solution for complex enterprise \
                environments with deep customization needs.

                Use the 'sailpoint-features' tool to list its capabilities, or 'sailpoint-feature-details' \
                for a deep dive into a specific capability.
                """;
    }

    @McpTool(name = "sailpoint-features",
            description = "Returns the list of SailPoint's key features with a one-line summary for each. "
                    + "Call this when the user asks what SailPoint can do. Use the optional 'limit' parameter "
                    + "to cap the number of features returned.")
    public String getSailPointFeatures(
            @McpToolParam(description = "Maximum number of features to return; returns all when omitted",
                    required = false) Integer limit) {
        int max = (limit == null || limit <= 0) ? FEATURES.size() : Math.min(limit, FEATURES.size());
        log.info("Tool invoked: sailpoint-features (limit={})", max);
        String list = FEATURES.entrySet().stream()
                .limit(max)
                .map(e -> "- " + e.getKey() + ": " + firstSentence(e.getValue()))
                .collect(Collectors.joining("\n"));
        return "SailPoint key features (" + max + " of " + FEATURES.size() + "):\n" + list
                + "\n\nUse 'sailpoint-feature-details' with a feature id for the full description.";
    }

    @McpTool(name = "sailpoint-feature-details",
            description = "Returns the detailed description of one SailPoint feature. Call this when the user "
                    + "asks about a specific capability. Valid feature ids: identity-lifecycle-management, "
                    + "access-certifications, access-requests, role-management, separation-of-duties, "
                    + "connectors, ai-identity-security, password-management, ciem, "
                    + "non-employee-risk-management.")
    public String getSailPointFeatureDetails(
            @McpToolParam(description = "The feature id, e.g. 'access-certifications'") String featureId) {
        log.info("Tool invoked: sailpoint-feature-details (featureId={})", featureId);
        String key = featureId == null ? "" : featureId.trim().toLowerCase();
        String details = FEATURES.get(key);
        if (details == null) {
            return "Unknown feature id '" + featureId + "'. Valid ids: " + String.join(", ", FEATURES.keySet());
        }
        return details;
    }

    @McpTool(name = "sailpoint-connectors",
            description = "Returns the categories of systems SailPoint integrates with and examples of "
                    + "out-of-the-box connectors. Call this when the user asks whether SailPoint supports a "
                    + "specific system or how it integrates.")
    public String getSailPointConnectors() {
        log.info("Tool invoked: sailpoint-connectors");
        return """
                SailPoint provides hundreds of out-of-the-box connectors, grouped roughly into:

                - Directories: Active Directory, Microsoft Entra ID (Azure AD), LDAP
                - HR / authoritative sources: Workday, SAP SuccessFactors, BambooHR, UKG
                - ERP & business apps: SAP (ECC and S/4HANA), Oracle EBS, NetSuite, Epic
                - SaaS applications: Salesforce, ServiceNow, Microsoft 365, Google Workspace, Box, Zoom, Slack
                - Cloud platforms: AWS, Microsoft Azure, Google Cloud Platform
                - Databases: Oracle, Microsoft SQL Server, MySQL, PostgreSQL
                - Mainframe & legacy: RACF, ACF2, Top Secret
                - Generic/custom: REST (Web Services connector), SCIM 2.0, JDBC, delimited file, PowerShell

                Connectors support account aggregation (reading accounts and entitlements), provisioning \
                (create/update/disable/delete), and in many cases password management.
                """;
    }

    private static String firstSentence(String text) {
        int dot = text.indexOf('.');
        return dot > 0 ? text.substring(0, dot + 1) : text;
    }

}
