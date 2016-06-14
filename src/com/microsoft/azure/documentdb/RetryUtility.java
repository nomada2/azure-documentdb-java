package com.microsoft.azure.documentdb;

/**
 * A utility class to manage retries for various retryable service errors. It
 * invokes a delegate function to execute the target operation. Upon error, it
 * waits a period of time as defined by the RetryPolicy instance before
 * re-issuing the same request. Different RetryPolicy implementations decides
 * the wait period based on its own logic.
 * 
 */
class RetryUtility {

    /**
     * Executes the code block in the delegate and retry if needed.
     * 
     * <p>
     * This method is used to retry an existing DocumentServiceRequest.
     * 
     * @param delegate
     *            the delegate to execute.

     * @param documentClient
     *            the DocumentClient instance.
     *            
     * @param request
     *            the request parameter for the execution.

     * @throws DocumentClientException
     *             the original exception if retry is not applicable.
     */
    public static DocumentServiceResponse execute(RetryRequestDelegate delegate, DocumentClient documentClient,
            DocumentServiceRequest request) throws DocumentClientException {
        DocumentServiceResponse response = null;

        EndpointDiscoveryRetryPolicy discoveryRetryPolicy = new EndpointDiscoveryRetryPolicy(documentClient);
        ResourceThrottleRetryPolicy throttleRetryPolicy = new ResourceThrottleRetryPolicy(
                documentClient.getConnectionPolicy().getRetryOptions().getMaxRetryAttemptsOnThrottledRequests(),
                documentClient.getConnectionPolicy().getRetryOptions().getMaxRetryWaitTimeInSeconds());
        SessionReadRetryPolicy sessionReadRetryPolicy = new SessionReadRetryPolicy(
                documentClient.getGlobalEndpointManager(), request);

        while (true) {
            try {
                response = delegate.apply(request);
                break;
            } catch (DocumentClientException e) {
                RetryPolicy retryPolicy = null;
                if (e.getStatusCode() == HttpConstants.StatusCodes.FORBIDDEN && e.getSubStatusCode() != null
                        && e.getSubStatusCode() == HttpConstants.SubStatusCodes.FORBIDDEN_WRITEFORBIDDEN) {
                    // If HttpStatusCode is 403 (Forbidden) and SubStatusCode is
                    // 3 (WriteForbidden),
                    // invoke the endpoint discovery retry policy
                    retryPolicy = discoveryRetryPolicy;
                } else if (e.getStatusCode() == HttpConstants.StatusCodes.TOO_MANY_REQUESTS) {
                    // If HttpStatusCode is 429 (Too Many Requests), invoke the
                    // throttle retry policy
                    retryPolicy = throttleRetryPolicy;
                } else if (e.getStatusCode() == HttpConstants.StatusCodes.NOTFOUND && e.getSubStatusCode() != null
                        && e.getSubStatusCode() == HttpConstants.SubStatusCodes.READ_SESSION_NOT_AVAILABLE) {
                    // If HttpStatusCode is 404 (NotFound) and SubStatusCode is
                    // 1002 (ReadSessionNotAvailable), invoke the session read retry policy
                    retryPolicy = sessionReadRetryPolicy;
                }

                boolean retry = (retryPolicy != null && retryPolicy.shouldRetry(e));
                if (!retry) {
                    throw e;
                }

                RetryUtility.delayForRetry(retryPolicy);
            }
        }

        return response;
    }
    
    /**
     * Executes the code block in the delegate and retry if needed.
     * 
     * <p>
     * This method is used to retry a document create operation for partitioned collection
     * in the case where the request failed because the collection has changed.
     * 
     * @param delegate
     *            the delegate to execute.

     * @param documentClient
     *            the DocumentClient instance.

     * @param resourcePath
     *            the path to the collection resource.
     *            
     * @throws DocumentClientException
     *             the original exception if retry is not applicable.
     */
    public static ResourceResponse<Document> execute(
            RetryCreateDocumentDelegate delegate, 
            DocumentClient documentClient,
            String resourcePath)
            throws DocumentClientException {
        ResourceResponse<Document> result = null;

        PartitionKeyMismatchRetryPolicy keyMismatchRetryPolicy = new  PartitionKeyMismatchRetryPolicy(
                resourcePath,
                documentClient.getPartitionKeyDefinitionMap());

        while (true) {
            try {
                result = delegate.apply();
                break;
            } catch (DocumentClientException e) {
                RetryPolicy retryPolicy = null;
                if (e.getStatusCode() == HttpConstants.StatusCodes.BADREQUEST && e.getSubStatusCode() != null
                        && e.getSubStatusCode() == HttpConstants.SubStatusCodes.PARTITION_KEY_MISMATCH) {
                    // If HttpStatusCode is 404 (NotFound) and SubStatusCode is
                    // 1001 (PartitionKeyMismatch), invoke the partition key mismatch retry policy
                    retryPolicy = keyMismatchRetryPolicy;
                }

                boolean retry = (retryPolicy != null && retryPolicy.shouldRetry(e));
                if (!retry) {
                    throw e;
                }

                RetryUtility.delayForRetry(retryPolicy);
            }
        }

        return result;
    }

    private static void delayForRetry(RetryPolicy retryPolicy) {
        final long delay = retryPolicy.getRetryAfterInMilliseconds();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                // Ignore the interruption.
            }
        }
    }
}
