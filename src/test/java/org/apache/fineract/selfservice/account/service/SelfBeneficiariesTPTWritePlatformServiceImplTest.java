/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.selfservice.account.data.SelfBeneficiariesTPTDataValidator;
import org.apache.fineract.selfservice.account.domain.SelfBeneficiariesTPT;
import org.apache.fineract.selfservice.account.domain.SelfBeneficiariesTPTRepository;
import org.apache.fineract.selfservice.account.exception.InvalidAccountInformationException;
import org.apache.fineract.selfservice.account.exception.InvalidBeneficiaryException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MX-515: an external beneficiary (SINPE, SINPE_MOVIL, PIN) is stored with the destination the
 * client gave in destinationIban or phoneNumber, the same value the validator checked.
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
    ArgumentCaptor<SelfBeneficiariesTPT> captor =
        ArgumentCaptor.forClass(SelfBeneficiariesTPT.class);
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
            external(
                "PIN", "\"accountNumber\":\"000000999\",\"destinationIban\":\"" + IBAN + "\"")));

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

  private SelfBeneficiariesTPT existing(final boolean active) {
    SelfBeneficiariesTPT beneficiary =
        new SelfBeneficiariesTPT(7L, "Ana", 1L, 2L, 3L, 2, 1000L, "SAME_BANK", "CRC");
    beneficiary.setActive(active);
    setId(beneficiary, 5L);
    when(repository.findByIdForUpdate(5L)).thenReturn(Optional.of(beneficiary));
    return beneficiary;
  }

  private SelfBeneficiariesTPT existingWithName(final String name, final Long id) {
    SelfBeneficiariesTPT beneficiary =
        new SelfBeneficiariesTPT(7L, name, 1L, 2L, 3L, 2, 1000L, "SAME_BANK", "CRC");
    beneficiary.setActive(true);
    setId(beneficiary, id);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(beneficiary));
    return beneficiary;
  }

  private void setId(Object entity, Long id) {
    Class<?> clazz = entity.getClass();
    while (clazz != null) {
      try {
        Field idField = clazz.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
        return;
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalStateException("Field 'id' not found on " + entity.getClass());
  }

  // MX-518: a deleted beneficiary is hidden from every read, so it can't be updated either.
  @Test
  void aDeletedBeneficiaryCannotBeUpdated() {
    existing(false);

    assertThrows(
        InvalidBeneficiaryException.class,
        () -> service.update(5L, command("{\"name\":\"Mom\",\"transferLimit\":5000}")));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void anActiveBeneficiaryIsStillUpdated() {
    SelfBeneficiariesTPT beneficiary = existing(true);

    service.update(5L, command("{\"name\":\"Mom\",\"transferLimit\":5000}"));

    verify(repository).saveAndFlush(beneficiary);
    assertEquals("Mom", beneficiary.getName());
    assertEquals(5000L, beneficiary.getTransferLimit());
  }

  // MX-522: a field that is not in the request keeps its value.
  @Test
  void aRenameKeepsTheTransferLimit() {
    SelfBeneficiariesTPT beneficiary = existing(true);

    service.update(5L, command("{\"name\":\"Mom\"}"));

    verify(repository).saveAndFlush(beneficiary);
    assertEquals("Mom", beneficiary.getName());
    assertEquals(1000L, beneficiary.getTransferLimit());
  }

  @Test
  void aNewTransferLimitKeepsTheName() {
    SelfBeneficiariesTPT beneficiary = existing(true);

    service.update(5L, command("{\"transferLimit\":300}"));

    verify(repository).saveAndFlush(beneficiary);
    assertEquals("Ana", beneficiary.getName());
    assertEquals(300L, beneficiary.getTransferLimit());
  }

  @Test
  void anUnchangedValueSavesNothing() {
    existing(true);

    service.update(5L, command("{\"name\":\"Ana\"}"));

    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void deleteBeneficiary_with50CharacterName_shouldSoftDeleteSuccessfully() {
    String name50 = "A".repeat(50);
    SelfBeneficiariesTPT beneficiary = existingWithName(name50, 5L);

    CommandProcessingResult result = service.delete(5L, command("{}"));

    assertEquals(5L, result.getResourceId());
    assertFalse(beneficiary.isActive());
    verify(repository).save(beneficiary);
  }

  @Test
  void deleteBeneficiary_verifyDatabaseNameDoesNotExceed50Chars() {
    String name50 = "A".repeat(50);
    existingWithName(name50, 5L);

    service.delete(5L, command("{}"));

    ArgumentCaptor<SelfBeneficiariesTPT> captor =
        ArgumentCaptor.forClass(SelfBeneficiariesTPT.class);
    verify(repository).save(captor.capture());
    SelfBeneficiariesTPT saved = captor.getValue();

    assertNotNull(saved.getName());
    assertTrue(
        saved.getName().length() <= 50,
        "Saved beneficiary name must not exceed 50 characters, but was: "
            + saved.getName().length());
    assertTrue(saved.getName().contains("_del_5_"));
    assertTrue(saved.getName().startsWith("A"));
    assertFalse(saved.isActive());
  }

  @Test
  void deleteBeneficiary_withLargeIdAnd50CharName_doesNotExceed50Chars() {
    String name50 = "B".repeat(50);
    existingWithName(name50, 9876543210L);

    service.delete(9876543210L, command("{}"));

    ArgumentCaptor<SelfBeneficiariesTPT> captor =
        ArgumentCaptor.forClass(SelfBeneficiariesTPT.class);
    verify(repository).save(captor.capture());
    SelfBeneficiariesTPT saved = captor.getValue();

    assertTrue(
        saved.getName().length() <= 50,
        "Saved beneficiary name must not exceed 50 characters, but was: "
            + saved.getName().length());
    assertTrue(saved.getName().contains("_del_9876543210_"));
    assertFalse(saved.isActive());
  }

  @Test
  void deleteBeneficiary_withShortName_preservesFullOriginalName() {
    existingWithName("Ana", 5L);

    service.delete(5L, command("{}"));

    ArgumentCaptor<SelfBeneficiariesTPT> captor =
        ArgumentCaptor.forClass(SelfBeneficiariesTPT.class);
    verify(repository).save(captor.capture());
    SelfBeneficiariesTPT saved = captor.getValue();

    assertTrue(saved.getName().startsWith("Ana_del_5_"));
    assertTrue(saved.getName().length() <= 50);
    assertFalse(saved.isActive());
  }

  @Test
  void deleteBeneficiary_alreadySoftDeleted_returnsEarlyWithoutSaving() {
    existing(false);

    CommandProcessingResult result = service.delete(5L, command("{}"));

    assertEquals(5L, result.getResourceId());
    verify(repository, never()).save(any());
  }

  @Test
  void deleteBeneficiary_ownedByDifferentUser_throwsInvalidBeneficiaryException() {
    SelfBeneficiariesTPT beneficiary =
        new SelfBeneficiariesTPT(99L, "Ana", 1L, 2L, 3L, 2, 1000L, "SAME_BANK", "CRC");
    setId(beneficiary, 5L);
    when(repository.findByIdForUpdate(5L)).thenReturn(Optional.of(beneficiary));

    assertThrows(InvalidBeneficiaryException.class, () -> service.delete(5L, command("{}")));
    verify(repository, never()).save(any());
  }

  @Test
  void deleteBeneficiary_notFound_throwsInvalidBeneficiaryException() {
    when(repository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

    assertThrows(InvalidBeneficiaryException.class, () -> service.delete(999L, command("{}")));
    verify(repository, never()).save(any());
  }
}
