/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes one structured, secret-free INFO line per step-up event, so operators can follow the
 * lifecycle in the logs. Consumers needing the data programmatically subscribe to {@link
 * StepUpEvent} themselves.
 */
@Component
@Slf4j
public class StepUpEventLogger {

  @EventListener
  public void onStepUpEvent(final StepUpEvent event) {
    log.info(
        "STEP_UP {} challengeId={} userId={} action={} channel={} reason={}",
        event.type(),
        event.challengeId(),
        event.userId(),
        event.action(),
        event.channel(),
        event.reason());
  }
}
