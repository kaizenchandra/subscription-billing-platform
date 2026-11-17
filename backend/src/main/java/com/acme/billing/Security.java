package com.acme.billing;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
class Security {
 @Bean SecurityFilterChain filter(HttpSecurity http,@Value("${billing.demo}") boolean demo) throws Exception {
  http.csrf(c->c.disable());
  if(demo) http.authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/api/v1/webhooks/payments/razorpay").permitAll().anyRequest().authenticated()).httpBasic(b->{});
  else http.authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/api/v1/webhooks/payments/razorpay").permitAll().anyRequest().authenticated()).oauth2ResourceServer(o->o.jwt(j->{}));
  return http.build();
 }
 @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="billing.demo",havingValue="true")
 org.springframework.security.core.userdetails.UserDetailsService users(){return new org.springframework.security.provisioning.InMemoryUserDetailsManager(org.springframework.security.core.userdetails.User.withUsername("demo").password("{noop}local-only").roles("FINANCE").build(),org.springframework.security.core.userdetails.User.withUsername("customer").password("{noop}local-only").roles("CUSTOMER").build());}
}
