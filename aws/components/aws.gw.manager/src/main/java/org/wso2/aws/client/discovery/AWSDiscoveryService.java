package org.wso2.aws.client.discovery;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.builder.AWSAPIBuilder;
import org.wso2.aws.client.builder.AWSAPIBuilderFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;

import java.util.function.Consumer;

/**
 * Generic discovery loop for AWS API Gateway APIs.
 *
 * <p>Iterates over raw SDK items (from paginator {@code .items()}), uses the
 * {@link AWSAPIBuilderFactory} to locate the correct builder via {@code canHandle()},
 * builds WSO2 API objects using the template-method {@code build()}, and pushes
 * {@link DiscoveredAPI} results to a consumer.
 */
public class AWSDiscoveryService {

    private static final Log log = LogFactory.getLog(AWSDiscoveryService.class);

    /**
     * Discovers APIs from a stream of raw SDK objects.
     *
     * @param <T>      The type of raw SDK object (RestApi for V1, Api for V2).
     * @param items    Iterable of raw SDK API objects (from paginator.items()).
     * @param factory  The builder factory to select the right builder for each item.
     * @param env      The WSO2 environment context.
     * @param org      The organization identifier.
     * @param consumer Consumer that receives each successfully built DiscoveredAPI.
     */
    public <T> void discover(
            Iterable<T> items,
            AWSAPIBuilderFactory factory,
            Environment env,
            String org,
            Consumer<DiscoveredAPI> consumer) {

        for (T raw : items) {
            try {
                AWSAPIBuilder builder = factory.getBuilder(raw);
                API api = builder.build(raw, env, org);
                if (api != null) {
                    String reference = builder.createReferenceArtifact(raw);
                    consumer.accept(new DiscoveredAPI(api, reference));
                }
            } catch (APIManagementException e) {
                log.debug("Skipping API: " + e.getMessage());
            } catch (IllegalStateException e) {
                log.warn("No builder registered for API type: " + raw.getClass().getSimpleName(), e);
            } catch (Exception e) {
                log.error("Error building API from raw object: " + raw, e);
            }
        }
    }
}
