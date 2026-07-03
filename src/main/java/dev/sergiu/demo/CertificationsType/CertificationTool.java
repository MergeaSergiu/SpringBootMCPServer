package dev.sergiu.demo.CertificationsType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class CertificationTool {

    private static final Logger log = LoggerFactory.getLogger(CertificationTool.class);

    private static final Map<String, String> CERTIFICATION_TYPES = new LinkedHashMap<>();

    static {
        CERTIFICATION_TYPES.put("manager", """
                Manager Certification: managers review and certify the access (entitlements, roles, accounts) \
                of their direct reports. The certification is built from the manager hierarchy of the identity \
                cube, so each manager only sees their own team. This is the most common recurring campaign \
                type, typically scheduled quarterly or semi-annually.""");
        CERTIFICATION_TYPES.put("application-owner", """
                Application Owner Certification: the owner of an application reviews all identities that hold \
                accounts or entitlements on that application. Useful when access should be validated by the \
                person responsible for the system rather than by the users' managers. One certification is \
                generated per application, assigned to its configured owner.""");
        CERTIFICATION_TYPES.put("entitlement-owner", """
                Entitlement Owner Certification: the owner of an individual entitlement (for example an AD \
                group or an SAP profile) reviews every identity that holds that entitlement. This gives the \
                most granular business-level review, since each entitlement owner certifies only the access \
                they are responsible for.""");
        CERTIFICATION_TYPES.put("role-membership", """
                Role Membership Certification: role owners review the identities that are assigned to their \
                roles and decide whether each identity should keep the role. Revoking a membership removes the \
                role (and, through provisioning, the entitlements it grants) from the identity.""");
        CERTIFICATION_TYPES.put("role-composition", """
                Role Composition Certification: role owners review the contents of the roles themselves - the \
                profiles, entitlements, and inherited/required/permitted role relationships that make up each \
                role. This certifies the role model rather than user access, keeping roles from silently \
                accumulating entitlements over time.""");
        CERTIFICATION_TYPES.put("account-group-membership", """
                Account Group Membership Certification: reviews which accounts are members of an account group \
                on a specific application (for example, who is in an Active Directory group). The certifier is \
                the account group owner or a designated reviewer.""");
        CERTIFICATION_TYPES.put("account-group-permissions", """
                Account Group Permissions Certification: reviews the permissions that an account group itself \
                holds on an application (what the group grants), as opposed to who is in the group. Together \
                with account group membership certifications it covers both sides of group-based access.""");
        CERTIFICATION_TYPES.put("advanced", """
                Advanced Certification: certifies the identities in a custom population (iPOP) or group, \
                defined by a filter or rule (for example 'all contractors in Finance'). Use it when the review \
                scope doesn't map to a manager hierarchy, an application, or a role - the population definition \
                drives who gets certified.""");
        CERTIFICATION_TYPES.put("targeted", """
                Targeted Certification: the most flexible type (recommended in current IdentityIQ versions). \
                Lets you choose exactly WHO is certified (identities selected by filter, population, or rule), \
                WHAT is certified (roles, entitlements, accounts, target permissions), and WHO certifies \
                (manager, owner, or a rule-selected reviewer) in one campaign definition. Most new campaigns \
                should be modeled as targeted certifications.""");
        CERTIFICATION_TYPES.put("identity", """
                Identity Certification: an ad-hoc certification of one or more specific identities, launched \
                directly from the identity pages (for example after a security incident or a role change). \
                The certifier reviews all of the selected identities' access in one place.""");
        CERTIFICATION_TYPES.put("event-based", """
                Event-Based Certification: triggered automatically by a lifecycle event on an identity - \
                typically a manager change, department transfer, or reactivation. Instead of running on a \
                schedule, the certification is generated for the affected identity when the trigger fires, so \
                access is re-validated exactly when risk is introduced.""");
    }

    //tools
    @McpTool(name = "identityiq-certification-types",
            description = "Returns the list of certification types available in SailPoint IdentityIQ with a "
                    + "one-line summary for each. Call this when the user asks what kinds of certifications "
                    + "or access reviews IdentityIQ supports. Use the optional 'limit' parameter to cap the "
                    + "number of types returned.")
    public String getCertificationTypes(
            @McpToolParam(description = "Maximum number of certification types to return; returns all when omitted",
                    required = false) Integer limit) {
        int max = (limit == null || limit <= 0) ? CERTIFICATION_TYPES.size()
                : Math.min(limit, CERTIFICATION_TYPES.size());
        log.info("Tool invoked: identityiq-certification-types (limit={})", max);
        String list = CERTIFICATION_TYPES.entrySet().stream()
                .limit(max)
                .map(e -> "- " + e.getKey() + ": " + firstSentence(e.getValue()))
                .collect(Collectors.joining("\n"));
        return "SailPoint IdentityIQ certification types (" + max + " of " + CERTIFICATION_TYPES.size() + "):\n"
                + list
                + "\n\nUse 'identityiq-certification-details' with a certification type id for the full description.";
    }

    @McpTool(name = "identityiq-certification-details",
            description = "Returns the detailed description of one SailPoint IdentityIQ certification type: "
                    + "who certifies, what is reviewed, and when to use it. Valid type ids: manager, "
                    + "application-owner, entitlement-owner, role-membership, role-composition, "
                    + "account-group-membership, account-group-permissions, advanced, targeted, identity, "
                    + "event-based.")
    public String getCertificationTypeDetails(
            @McpToolParam(description = "The certification type id, e.g. 'manager'") String certificationType) {
        log.info("Tool invoked: identityiq-certification-details (certificationType={})", certificationType);
        String key = certificationType == null ? "" : certificationType.trim().toLowerCase();
        String details = CERTIFICATION_TYPES.get(key);
        if (details == null) {
            return "Unknown certification type '" + certificationType + "'. Valid ids: "
                    + String.join(", ", CERTIFICATION_TYPES.keySet());
        }
        return details;
    }

    private static String firstSentence(String text) {
        int dot = text.indexOf('.');
        return dot > 0 ? text.substring(0, dot + 1) : text;
    }
}
