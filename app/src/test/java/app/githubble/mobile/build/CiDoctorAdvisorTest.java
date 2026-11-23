package app.githubble.mobile.build;

import org.junit.Test;
import static org.junit.Assert.*;

/** CI Doctor may explain a failure, but cannot authorize an unsafe repair. */
public class CiDoctorAdvisorTest {
    @Test public void infrastructureIsRetryableButNotSourceFixable() {
        CiDoctorAdvisor.Advice advice=CiDoctorAdvisor.explain("RUNNER_FAILURE");
        assertTrue(advice.transientFailure);
        assertFalse(advice.sourceFixable);
        assertTrue(advice.title.contains("Runner"));
    }

    @Test public void compilationMayPrepareReviewedSourceFix() {
        CiDoctorAdvisor.Advice advice=CiDoctorAdvisor.explain("KOTLIN_JAVA_ERROR");
        assertTrue(advice.sourceFixable);
        assertFalse(advice.transientFailure);
        assertTrue(advice.nextStep.contains("PR"));
    }

    @Test public void billingNeverSuggestsSourceChangesOrRetry() {
        CiDoctorAdvisor.Advice advice=CiDoctorAdvisor.explain("BILLING_OR_QUOTA_BLOCK");
        assertFalse(advice.sourceFixable);
        assertFalse(advice.transientFailure);
        assertTrue(advice.nextStep.contains("Billing"));
    }

    @Test public void signingNeverSuggestsSendingKeys() {
        CiDoctorAdvisor.Advice advice=CiDoctorAdvisor.explain("SIGNING_FAILURE");
        assertFalse(advice.sourceFixable);
        assertTrue(advice.nextStep.contains("Не загружайте ключи"));
    }

    @Test public void unknownCategoryFailsClosed() {
        for (String input : new String[]{"SOMETHING_NEW","","unknown"}) {
            CiDoctorAdvisor.Advice advice=CiDoctorAdvisor.explain(input);
            assertFalse(advice.sourceFixable);
            assertFalse(advice.transientFailure);
        }
        assertFalse(CiDoctorAdvisor.explain(null).sourceFixable);
    }

    @Test public void originalTaxonomyPreventsMisleadingManualRetry() {
        assertFalse(CiDoctorAdvisor.explain("DEPENDENCY_RESOLUTION_ERROR").transientFailure);
        assertTrue(CiDoctorAdvisor.explain("TEST_FAILURE").sourceFixable);
        assertTrue(CiDoctorAdvisor.explain("API_RATE_LIMIT").transientFailure);
        assertFalse(CiDoctorAdvisor.explain("PERMISSION_ERROR").sourceFixable);
    }
}
