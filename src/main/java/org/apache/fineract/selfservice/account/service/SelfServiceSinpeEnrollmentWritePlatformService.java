package org.apache.fineract.selfservice.account.service;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

public interface SelfServiceSinpeEnrollmentWritePlatformService {
  CommandProcessingResult requestEnrollment(String mobileNumber);

  CommandProcessingResult confirmEnrollment(String mobileNumber, String otp);
}
