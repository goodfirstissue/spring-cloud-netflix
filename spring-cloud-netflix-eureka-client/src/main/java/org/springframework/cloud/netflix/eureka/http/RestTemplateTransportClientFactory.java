/*
 * Copyright 2017-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.eureka.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.converters.jackson.mixin.ApplicationsJsonMixIn;
import com.netflix.discovery.converters.jackson.mixin.InstanceInfoJsonMixIn;
import com.netflix.discovery.converters.jackson.serializer.InstanceInfoJsonBeanSerializer;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;

import org.springframework.cloud.configuration.SSLContextFactory;
import org.springframework.cloud.configuration.TlsProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Provides the custom {@link RestTemplate} required by the
 * {@link RestTemplateEurekaHttpClient}. Relies on Jackson for serialization and
 * deserialization.
 *
 * @author Daniel Lavoie
 */
public class RestTemplateTransportClientFactory implements TransportClientFactory {

	private final Optional<SSLContext> sslContext;

	private final Optional<HostnameVerifier> hostnameVerifier;

	public RestTemplateTransportClientFactory(TlsProperties tlsProperties) {
		this.sslContext = context(tlsProperties);
		this.hostnameVerifier = Optional.empty();
	}

	private Optional<SSLContext> context(TlsProperties properties) {
		if (properties == null || !properties.isEnabled()) {
			return Optional.empty();
		}
		try {
			return Optional.of(new SSLContextFactory(properties).createSSLContext());
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public RestTemplateTransportClientFactory(Optional<SSLContext> sslContext,
			Optional<HostnameVerifier> hostnameVerifier) {
		this.sslContext = sslContext;
		this.hostnameVerifier = hostnameVerifier;
	}

	public RestTemplateTransportClientFactory() {
		this.sslContext = Optional.empty();
		this.hostnameVerifier = Optional.empty();
	}

	@Override
	public EurekaHttpClient newClient(EurekaEndpoint serviceUrl) {
		return new RestTemplateEurekaHttpClient(restTemplate(serviceUrl.getServiceUrl()), serviceUrl.getServiceUrl());
	}

	private RestTemplate restTemplate(String serviceUrl) {
		RestTemplate restTemplate = restTemplate();

		try {
			URI serviceURI = new URI(serviceUrl);
			if (serviceURI.getUserInfo() != null) {
				String[] credentials = serviceURI.getUserInfo().split(":");
				if (credentials.length == 2) {
					restTemplate.getInterceptors()
							.add(new BasicAuthenticationInterceptor(credentials[0], credentials[1]));
				}
			}
		}
		catch (URISyntaxException ignore) {

		}

		restTemplate.getMessageConverters().add(0, mappingJacksonHttpMessageConverter());
		restTemplate.setErrorHandler(new ErrorHandler());

		return restTemplate;
	}

	private RestTemplate restTemplate() {
		if (this.sslContext.isPresent()) {
			SSLContext sslContext = this.sslContext.get();
			// TODO: Using HttpClient here - maybe this should be configurable?
			HttpClientBuilder httpClientBuilder = HttpClients.custom().setSSLContext(sslContext);
			if (this.hostnameVerifier.isPresent()) {
				httpClientBuilder = httpClientBuilder.setSSLHostnameVerifier(this.hostnameVerifier.get());
			}
			CloseableHttpClient httpClient = httpClientBuilder.build();
			HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
			requestFactory.setHttpClient(httpClient);
			return new RestTemplate(requestFactory);
		}
		return new RestTemplate();
	}

	/**
	 * Provides the serialization configurations required by the Eureka Server. JSON
	 * content exchanged with eureka requires a root node matching the entity being
	 * serialized or deserialized. Achived with
	 * {@link SerializationFeature#WRAP_ROOT_VALUE} and
	 * {@link DeserializationFeature#UNWRAP_ROOT_VALUE}.
	 * {@link PropertyNamingStrategy.SnakeCaseStrategy} is applied to the underlying
	 * {@link ObjectMapper}.
	 * @return a {@link MappingJackson2HttpMessageConverter} object
	 */
	public MappingJackson2HttpMessageConverter mappingJacksonHttpMessageConverter() {
		MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
		converter.setObjectMapper(new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE));

		SimpleModule jsonModule = new SimpleModule();
		jsonModule.setSerializerModifier(createJsonSerializerModifier()); // keyFormatter,
		// compact));
		converter.getObjectMapper().registerModule(jsonModule);

		converter.getObjectMapper().configure(SerializationFeature.WRAP_ROOT_VALUE, true);
		converter.getObjectMapper().configure(DeserializationFeature.UNWRAP_ROOT_VALUE, true);
		converter.getObjectMapper().addMixIn(Applications.class, ApplicationsJsonMixIn.class);
		converter.getObjectMapper().addMixIn(InstanceInfo.class, InstanceInfoJsonMixIn.class);

		// converter.getObjectMapper().addMixIn(DataCenterInfo.class,
		// DataCenterInfoXmlMixIn.class);
		// converter.getObjectMapper().addMixIn(InstanceInfo.PortWrapper.class,
		// PortWrapperXmlMixIn.class);
		// converter.getObjectMapper().addMixIn(Application.class,
		// ApplicationXmlMixIn.class);
		// converter.getObjectMapper().addMixIn(Applications.class,
		// ApplicationsXmlMixIn.class);

		return converter;
	}

	public static BeanSerializerModifier createJsonSerializerModifier() { // final
		// KeyFormatter
		// keyFormatter,
		// final
		// boolean
		// compactMode)
		// {
		return new BeanSerializerModifier() {
			@Override
			public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc,
					JsonSerializer<?> serializer) {
				/*
				 * if (beanDesc.getBeanClass().isAssignableFrom(Applications.class)) {
				 * return new ApplicationsJsonBeanSerializer((BeanSerializerBase)
				 * serializer, keyFormatter); }
				 */
				if (beanDesc.getBeanClass().isAssignableFrom(InstanceInfo.class)) {
					return new InstanceInfoJsonBeanSerializer((BeanSerializerBase) serializer, false);
				}
				return serializer;
			}
		};
	}

	@Override
	public void shutdown() {
	}

	class ErrorHandler extends DefaultResponseErrorHandler {

		@Override
		protected boolean hasError(HttpStatus statusCode) {
			/**
			 * When the Eureka server restarts and a client tries to sent a heartbeat the
			 * server will respond with a 404. By default RestTemplate will throw an
			 * exception in this case. What we want is to return the 404 to the upstream
			 * code so it will send another registration request to the server.
			 */
			if (statusCode.is4xxClientError()) {
				return false;
			}
			return super.hasError(statusCode);
		}

	}

}
