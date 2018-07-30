package com.redhat.developer.demos.customer;

import io.jaegertracing.SpanContext;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format.Builtin;
import io.opentracing.propagation.TextMap;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
public class CustomerController {

    private static final String RESPONSE_STRING_FORMAT = "customer => %s\n";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final RestTemplate restTemplate;

    @Value("${preferences.api.url:http://preference:8080}")
    private String remoteURL;

    @Autowired
    private Tracer tracer;

    public CustomerController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @RequestMapping("/")
    public ResponseEntity<String> getCustomer(@RequestHeader("User-Agent") String userAgent) {
        try {
            /**
             * Set baggage
             */
            tracer.activeSpan().setBaggageItem("user-agent", userAgent);

            createURLRequest();

            Scope scope = tracer.buildSpan("wrapper").startActive(true);
            ResponseEntity<String> responseEntity = restTemplate.getForEntity(remoteURL, String.class);
            scope.close();

            String response = responseEntity.getBody();
            return ResponseEntity.ok(String.format(RESPONSE_STRING_FORMAT, response.trim()));
        } catch (HttpStatusCodeException ex) {
            logger.warn("Exception trying to get the response from preference service.", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(String.format(RESPONSE_STRING_FORMAT,
                            String.format("%d %s", ex.getRawStatusCode(), createHttpErrorResponseString(ex))));
        } catch (RestClientException ex) {
            logger.warn("Exception trying to get the response from preference service.", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(String.format(RESPONSE_STRING_FORMAT, ex.getMessage()));
        }
    }

    private String createHttpErrorResponseString(HttpStatusCodeException ex) {
        String responseBody = ex.getResponseBodyAsString().trim();
        if (responseBody.startsWith("null")) {
            return ex.getStatusCode().getReasonPhrase();
        }
        return responseBody;
    }


    private void createURLRequest() {
        io.jaegertracing.Tracer jaeger = (io.jaegertracing.Tracer) tracer;
        try (Scope scope = tracer.buildSpan("urlConnection").startActive(true)) {
            URL url = new URL("http://preference:8080");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            tracer.inject(tracer.activeSpan().context(), Builtin.HTTP_HEADERS, new HttpUrlConnectionInject(con));
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                System.out.println(inputLine);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private class HttpUrlConnectionInject implements TextMap {
        HttpURLConnection urlConnection;

        public HttpUrlConnectionInject(HttpURLConnection urlConnectionInject){
            this.urlConnection = urlConnectionInject;
        }

        @Override
        public Iterator<Entry<String, String>> iterator() {
            return null;
        }

        @Override
        public void put(String s, String s1) {
            urlConnection.setRequestProperty(s, s1);
        }
    }
}
