/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.selfservice.account.data.SelfBeneficiariesTPTDataValidator;
import org.apache.fineract.selfservice.account.domain.SelfBeneficiariesTPT;
import org.apache.fineract.selfservice.account.domain.SelfBeneficiariesTPTRepository;
import org.apache.fineract.selfservice.account.exception.InvalidAccountInformationException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MX-515: an external beneficiary (SINPE, SINPE_MOVIL, PIN) is stored with the destination the client gave in
 * destinationIban or phoneNumber, the same value the validator checked.
 */
class SelfBeneficiariesTPTWritePlatformServiceImplTest {

  private static final String IBAN = "CR21015201001026284066";
  private static final String PHONE = "88887777";

  private final PlatformSelfServiceSecurityContext context =
      mock(PlatformSelfServiceSecurityContext.class);
  private final SelfBeneficiariesTPTRepository repository =
      mock(SelfBeneficiariesTPTRepository.class);
  private SelfBeneficiariesTPTWritePlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getId()).thenReturn(7L);
    when(context.authenticatedSelfServiceUser()).thenReturn(user);
    service =
        new SelfBeneficiariesTPTWritePlatformServiceImpl(
            context,
            repository,
            new SelfBeneficiariesTPTDataValidator(new FromJsonHelper()),
            mock(LoanRepositoryWrapper.class),
            mock(SavingsAccountRepositoryWrapper.class),
            mock(LoanRepository.class),
            mock(SavingsAccountRepository.class));
  }

  private static final FromJsonHelper FROM_JSON = new FromJsonHelper();

  private static JsonCommand command(final String json) {
    return JsonCommand.fromJsonElement(1L, FROM_JSON.parse(json), FROM_JSON);
  }

  private static String external(final String paymentType, final String destination) {
    return "{\"locale\":\"en\",\"name\":\"Ana\",\"paymentType\":\""
        + paymentType
        + "\",\"currency\":\"CRC\",\"accountType\":3,\"transferLimit\":1000,"
        + destination
        + "}";
  }

  private SelfBeneficiariesTPT saved() {
    ArgumentCaptor<SelfBeneficiariesTPT> captor = ArgumentCaptor.forClass(SelfBeneficiariesTPT.class);
    verify(repository).saveAndFlush(captor.capture());
    return captor.getValue();
  }

  @Test
  void sinpeBeneficiaryWithOnlyAnIbanIsCreatedWithThatIban() {
    service.add(command(external("SINPE", "\"destinationIban\":\"" + IBAN + "\"")));

    assertEquals(IBAN, saved().getCustomAccountNumber());
  }

  @Test
  void theIbanWinsOverAnAccountNumberSentWithIt() {
    service.add(
        command(
            external("PIN", "\"accountNumber\":\"000000999\",\"destinationIban\":\"" + IBAN + "\"")));

    assertEquals(IBAN, saved().getCustomAccountNumber());
  }

  @Test
  void sinpeMovilBeneficiaryIsCreatedWithThePhoneNumber() {
    service.add(command(external("SINPE_MOVIL", "\"phoneNumber\":\"" + PHONE + "\"")));

    assertEquals(PHONE, saved().getCustomAccountNumber());
  }

  @Test
  void anAccountNumberAloneIsStillAccepted() {
    service.add(command(external("SINPE", "\"accountNumber\":\"" + IBAN + "\"")));

    assertEquals(IBAN, saved().getCustomAccountNumber());
  }

  @Test
  void anExternalBeneficiaryWithoutAnyDestinationIsRejected() {
    assertThrows(
        InvalidAccountInformationException.class,
        () -> service.add(command(external("SINPE", "\"holder\":\"Ana Mora\""))));
    verify(repository, never()).saveAndFlush(any());
  }
}
