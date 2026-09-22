package com.loop.orchestrator.web;

import com.loop.orchestrator.policy.ReviewerIndependence;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration findings a person should see where they are deciding something, not only
 * in a startup log they scrolled past days ago.
 *
 * <p>The board and gate-1 screens read this. A warning that lives only in stdout is read
 * once and then becomes wallpaper, which is the same reason a gate that fires on every
 * epic gets rubber-stamped inside a fortnight.
 */
@RestController
@RequestMapping("/policy")
public class PolicyController {

    private final ReviewerIndependence.Assessment reviewerIndependence;

    public PolicyController(ReviewerIndependence.Assessment reviewerIndependence) {
        this.reviewerIndependence = reviewerIndependence;
    }

    /**
     * 200 when the reviewer is independent, 409 when it is not — so a screen or a script
     * can act on the status alone without parsing the body.
     */
    @GetMapping("/reviewer-independence")
    public ResponseEntity<ReviewerIndependence.Assessment> reviewerIndependence() {
        return ResponseEntity
                .status(reviewerIndependence.independent() ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(reviewerIndependence);
    }
}
