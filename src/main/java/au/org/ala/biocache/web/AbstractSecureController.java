/**************************************************************************
 *  Copyright (C) 2013 Atlas of Living Australia
 *  All Rights Reserved.
 * 
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 * 
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/
package au.org.ala.biocache.web;

import au.org.ala.biocache.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Controllers that need to perform security checks should extend this class and call shouldPerformOperation.
 * 
 * NOTE: Even though this has "Abstract" in the class name for historical reasons, it is a non-abstract class.
 */
public class AbstractSecureController {

    private final static Logger logger = LoggerFactory.getLogger(AbstractSecureController.class);

    protected Supplier<Stream<IpAddressMatcher>> excludedNetworkStream;
    protected Supplier<Stream<IpAddressMatcher>> includedNetworkStream;

    /**
     * networks to exclude from rate limiting.
     * If the request IP address is within any of the networks then request will be excluded from rate limiting rules.
     *
     * @param networks array of network addresses in the format x.x.x.x/m
     */
    @Value("${ratelimit.network.exclude}")
    void setExcludedNetworks(String[] networks) {
        excludedNetworkStream = () -> Arrays.stream(networks)
                .map(IpAddressMatcher::new);
    }

    /**
     * networks to include in rate limiting.
     * If the request IP address is within any of the list if networks then the request will be subject to rate limiting rules.
     *
     * @param networks array of network addresses in the format x.x.x.x/m
     */
    @Value("${ratelimit.network.include:0.0.0.0/0}")
    void setIncludedNetworks(String[] networks) {
        includedNetworkStream = () -> Arrays.stream(networks)
                .map(IpAddressMatcher::new);
    }

    protected String[] acceptNetworks;

    @Value("${apikey.check.url:https://auth.ala.org.au/apikey/ws/check?apikey=}")
    protected String apiCheckUrl;

    @Value("${apikey.check.enabled:true}")
    protected Boolean apiKeyCheckedEnabled = true;

    /** 
     * Local cache of keys 
     * 
     * FIXME: Why is the cache static?
     **/
    private static Set<String> apiKeyCache = new ConcurrentHashSet<>();

    public AbstractSecureController(){}

    /**
     * Returns the IP address for the supplied request. It will look for the existence of
     * an X-Forwarded-For Header before extracting it from the request.
     * @param request
     * @return IP Address of the request
     */
    protected String getIPAddress(HttpServletRequest request) {

        String ipAddress = request.getParameter("ip");
        ipAddress = ipAddress == null ? request.getHeader("X-Forwarded-For"): ipAddress;

        return ipAddress == null ? request.getRemoteAddr(): ipAddress;
    }

    /**
     * Check if the request should be rate limited.
     * The request will be rate limited if there is no 'apiKey' OR 'email' request parameter
     * OR if the IP address of the request is not in the excludedNetworks OR in the includedNetworks
     *
     * @param request
     * @param response
     * @return if the request should be rate limited
     * @throws IOException
     */
    public boolean rateLimitRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {

        String apiKey = request.getParameter("apiKey");
        String email = request.getParameter("email");

        if (apiKey != null || email != null) {
            return false;
        }

        String ipAddress = getIPAddress(request);
        boolean ratelimitIp = true;
        if (excludedNetworkStream != null) {
            ratelimitIp &= excludedNetworkStream.get().noneMatch(networkMatcher -> networkMatcher.matches(ipAddress));
        }
        if (includedNetworkStream != null) {
            ratelimitIp |= includedNetworkStream.get().anyMatch(networkMatcher -> networkMatcher.matches(ipAddress));
        }

        if (!ratelimitIp) {
            return false;
        }

        response.sendError(HttpServletResponse.SC_FORBIDDEN, "API Key or email required");
        return true;
    }

    /**
     * Check the validity of the supplied key, returning false if the store is in read only mode.
     *
     * @param request The request to find the apiKey parameter from
     * @param response The response to check for {@link HttpServletResponse#isCommitted()} and to send errors on if the operation should not be committed
     * @return True if the store is not in read-only mode, the API key is valid, and the response has not already been committed, and false otherwise
     * @throws Exception If the store is in read-only mode, or the API key is invalid.
     */
    public boolean shouldPerformOperation(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String apiKey = request.getParameter("apiKey");
        return shouldPerformOperation(apiKey, response, true);
    }

    /**
     * Check the validity of the supplied key, returning false if the store is in read only mode.
     *
     * @param apiKey The API key to check
     * @param response The response to check for {@link HttpServletResponse#isCommitted()} and to send errors on if the operation should not be committed
     * @return True if the store is not in read-only mode, the API key is valid, and the response has not already been committed, and false otherwise
     * @throws Exception If the store is in read-only mode, or the API key is invalid.
     */
    public boolean shouldPerformOperation(String apiKey, HttpServletResponse response) throws Exception {
        return shouldPerformOperation(apiKey, response, true);
    }
    
    /**
     * Use a webservice to validate a key
     * 
     * @param keyToTest
     * @return True if API key checking is disabled, or the API key is valid, and false otherwise.
     */
    public boolean isValidKey(String keyToTest){

        if(!apiKeyCheckedEnabled){
            return true;
        }

        if(StringUtils.isBlank(keyToTest)){
            return false;
        }

    	if(apiKeyCache.contains(keyToTest)){
    		return true;
    	}
    	
		//check via a web service
		try {
			logger.debug("Checking api key: {}", keyToTest);
    		String url = apiCheckUrl + keyToTest;
    		ObjectMapper om = new ObjectMapper();
    		Map<String,Object> response = om.readValue(new URL(url), Map.class);
    		boolean isValid = (Boolean) response.get("valid");
    		logger.debug("Checking api key: {}, valid: {}", keyToTest, isValid);
    		if(isValid){
    			apiKeyCache.add(keyToTest);
    		}
    		return isValid; 
		} catch (Exception e){
			logger.error(e.getMessage(), e);
		}
		
    	return false;
    }

	/**
     * Returns true when the operation should be performed.
     *
     * @param apiKey The API key to check for validity, after appending it to the api.check.url property.
     * @param response The response to either send an error on, or return false if the {@link HttpServletResponse#isCommitted()} returns true.
     * @param checkReadOnly True to check {@link Store#isReadOnly()}
     * @return True if the operation is able to be performed
     * @throws Exception
     */
    public boolean shouldPerformOperation(String apiKey,HttpServletResponse response, boolean checkReadOnly) throws Exception {
        if(checkReadOnly && Store.isReadOnly()){
            response.sendError(HttpServletResponse.SC_CONFLICT, "Server is in read only mode.  Try again later.");
            return false;
        } else if(!isValidKey(apiKey)){
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "An invalid API Key was provided.");
            return false;
        } else if(response.isCommitted()) {
        	return false;
        }
        
        return true;
    }
}
