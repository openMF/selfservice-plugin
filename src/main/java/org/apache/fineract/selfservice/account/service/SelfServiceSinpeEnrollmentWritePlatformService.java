package org.apache.fineract.selfservice.account.service;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.selfservice.account.data.SinpeSubscriptionRequest;

public interface SelfServiceSinpeEnrollmentWritePlatformService {

  CommandProcessingResult requestEnrollment(String mobileNumber);

  CommandProcessingResult confirmEnrollment(String mobileNumber, String otp);

  CommandProcessingResult createSubscription(SinpeSubscriptionRequest request, String otp);

  CommandProcessingResult editSubscription(SinpeSubscriptionEditRequest request, String otp);

  CommandProcessingResult requestDeleteSubscription(String phoneNumber);

  CommandProcessingResult deleteSubscription(String phoneNumber, String otp);
}
