package dev.llmgateway.gateway.api;

import java.time.LocalDate;

import dev.llmgateway.gateway.auth.ClientIdentity;
import dev.llmgateway.gateway.usage.report.UsageReport;
import dev.llmgateway.gateway.usage.report.UsageReports;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usage and cost of the calling key (design note 40). Whose usage to show comes only from the
 * authenticated key, never from a parameter, so a client can't ask for another's.
 */
@RestController
public class UsageController {

    private final UsageReports reports;

    public UsageController(UsageReports reports) {
        this.reports = reports;
    }

    /** Dates are UTC days ({@code 2026-11-24}), both included. Default: the last 30 days. */
    @GetMapping(path = "/v1/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UsageReport> usage(
            @RequestAttribute(ClientIdentity.REQUEST_ATTRIBUTE) ClientIdentity client,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(reports.report(client.id(), from, to));
    }
}
