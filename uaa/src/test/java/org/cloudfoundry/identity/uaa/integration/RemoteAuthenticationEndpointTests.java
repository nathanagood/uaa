/*******************************************************************************
 *     Cloud Foundry 
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/

package org.cloudfoundry.identity.uaa.integration;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.cloudfoundry.identity.uaa.ServerRunning;
import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.oauth.UaaOauth2ErrorHandler;
import org.cloudfoundry.identity.uaa.test.UaaTestAccounts;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * @author Luke Taylor
 */
public class RemoteAuthenticationEndpointTests {
    @Rule
    public ServerRunning serverRunning = ServerRunning.isRunning();

    private UaaTestAccounts testAccounts = UaaTestAccounts.standard(serverRunning);

    @Test
    public void remoteAuthenticationSucceedsWithCorrectCredentials() throws Exception {
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> response = authenticate(testAccounts.getUserName(), testAccounts.getPassword(), null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(testAccounts.getUserName(), response.getBody().get("username"));
        assertEquals(testAccounts.getEmail(), response.getBody().get("email"));
    }

    @Test
    public void remoteAuthenticationSucceedsAndCreatesUser() throws Exception {
        String username = new RandomValueStringGenerator().generate();
        String origin =  new RandomValueStringGenerator().generate();
        Map<String,Object> info = new HashMap<>();
        info.put("source", "login");
        info.put("add_new", "true");
        info.put(Origin.ORIGIN, origin);
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> response = authenticate(username, null, info);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(username, response.getBody().get("username"));
        validateOrigin(username, null, origin, info);
    }

    @Test
    public void remoteAuthenticationFailsWithIncorrectCredentials() throws Exception {
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> response = authenticate(testAccounts.getUserName(), "wrong", null);
        assertFalse(HttpStatus.OK == response.getStatusCode());
        assertFalse(testAccounts.getUserName().equals(response.getBody().get("username")));
    }

    @Test
    public void validateLdapOrKeystoneOrigin() throws Exception {
        String profiles = System.getProperty("spring.profiles.active");
        if (profiles!=null && profiles.contains("ldap")) {
            validateOrigin("marissa3","ldap3",Origin.LDAP, null);
        } else if (profiles!=null && profiles.contains("keystone")) {
            validateOrigin("marissa2", "keystone", Origin.KEYSTONE, null);
        } else {
            validateOrigin(testAccounts.getUserName(), testAccounts.getPassword(), Origin.UAA, null);
        }
    }

    public void validateOrigin(String username, String password, String origin, Map<String,Object> info) throws Exception {
        ResponseEntity<Map> authResp = authenticate(username,password, info);
        assertEquals(HttpStatus.OK, authResp.getStatusCode());

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + getScimReadBearerToken());
        ResponseEntity<Map> response = serverRunning.getForObject("/Users" + "?attributes=id,userName,origin", Map.class, headers);
        Map<String, Object> results = response.getBody();
        assertEquals(HttpStatus.OK, response.getStatusCode());

        assertThat(((Integer) results.get("totalResults")), greaterThan(0));
        List<Map<String, Object>> list = (List<Map<String, Object>>) results.get("resources");
        boolean found = false;
        for (Map<String, Object> user : list) {
            assertThat(user, hasKey("id"));
            assertThat(user, hasKey("userName"));
            assertThat(user, hasKey(Origin.ORIGIN));
            assertThat(user, not(hasKey("name")));
            assertThat(user, not(hasKey("emails")));
            if (user.get("userName").equals(username)) {
                found = true;
                assertEquals(origin, user.get(Origin.ORIGIN));
            }
        }
        assertTrue(found);
    }

    private String getScimReadBearerToken() {
        HttpHeaders accessTokenHeaders = new HttpHeaders();
        String basicDigestHeaderValue = "Basic "
            + new String(Base64.encodeBase64((testAccounts.getAdminClientId() + ":" + testAccounts.getAdminClientSecret()).getBytes()));
        accessTokenHeaders.add("Authorization", basicDigestHeaderValue);

        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "client_credentials");
        params.add("client_id", testAccounts.getAdminClientId());
        params.add("scope", "scim.read");
        ResponseEntity<Map> tokenResponse = serverRunning.postForMap(serverRunning.getAccessTokenUri(), params, accessTokenHeaders);
        return (String) tokenResponse.getBody().get("access_token");
    }

    private String getLoginReadBearerToken() {
        HttpHeaders accessTokenHeaders = new HttpHeaders();
        String basicDigestHeaderValue = "Basic "
            + new String(Base64.encodeBase64(("login:loginsecret").getBytes()));
        accessTokenHeaders.add("Authorization", basicDigestHeaderValue);

        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "client_credentials");
        params.add("client_id", "login");
        params.add("scope", "oauth.login");
        ResponseEntity<Map> tokenResponse = serverRunning.postForMap(serverRunning.getAccessTokenUri(), params, accessTokenHeaders);
        return (String) tokenResponse.getBody().get("access_token");
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> authenticate(String username, String password, Map<String, Object> additionalParams) {
        RestTemplate restTemplate = new RestTemplate();
        // The default java.net client doesn't allow you to handle 4xx responses
        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory());
        if (restTemplate instanceof OAuth2RestTemplate) {
            OAuth2RestTemplate oAuth2RestTemplate = (OAuth2RestTemplate)restTemplate;
            oAuth2RestTemplate.setErrorHandler(new UaaOauth2ErrorHandler(oAuth2RestTemplate.getResource(), HttpStatus.Series.SERVER_ERROR));
        } else {
            restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
                @Override
                protected boolean hasError(HttpStatus statusCode) {
                    return statusCode.series() == HttpStatus.Series.SERVER_ERROR;
                }
            });
        }
        HttpHeaders headers = new HttpHeaders();
        if (additionalParams!=null) {
            headers.add("Authorization", "Bearer " + getLoginReadBearerToken());
        }
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));

        MultiValueMap<String, Object> parameters = new LinkedMultiValueMap<String, Object>();
        parameters.set("username", username);
        if (password!=null) {
            parameters.set("password", password);
        }
        if (additionalParams!=null) {
            parameters.setAll(additionalParams);
        }

        ResponseEntity<Map> result = restTemplate.exchange(serverRunning.getUrl("/authenticate"),
                        HttpMethod.POST, new HttpEntity<MultiValueMap<String, Object>>(parameters, headers), Map.class);
        return result;
    }
}
