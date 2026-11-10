package com.drift.backend.account.signup;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class EmailVerificationMailConfig {

	@Bean
	@ConditionalOnProperty("spring.mail.host")
	public EmailVerificationMailer smtpEmailVerificationMailer(JavaMailSender mailSender,
			@Value("${app.signup.from}") String from) {
		return new SmtpEmailVerificationMailer(mailSender, from);
	}

	static final class SmtpEmailVerificationMailer implements EmailVerificationMailer {

		private final JavaMailSender mailSender;
		private final String from;

		SmtpEmailVerificationMailer(JavaMailSender mailSender, String from) {
			this.mailSender = mailSender;
			this.from = from;
		}

		@Override
		public void sendVerificationLink(EmailVerificationMailer.VerificationEmail email) {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(from);
			message.setTo(email.to());
			message.setSubject("Verify your DRIFT account");
			message.setText(text(email));
			mailSender.send(message);
		}
	}

	static String text(EmailVerificationMailer.VerificationEmail email) {
		return """
				A DRIFT company account was requested with this email address.

				Company: %s (%s)
				Administrator: %s

				Open this link to verify your email and activate the company. It expires in %s.
				%s

				If you did not request this, ignore this email; the account will not be activated.
				""".formatted(email.companyName(), email.companyCode(), email.fullName(), describe(email.validFor()),
				email.link());
	}

	static String describe(Duration validFor) {
		long hours = validFor.toHours();
		if (hours > 0 && validFor.equals(Duration.ofHours(hours))) {
			return hours == 1 ? "1 hour" : hours + " hours";
		}
		long minutes = validFor.toMinutes();
		if (minutes > 0 && validFor.equals(Duration.ofMinutes(minutes))) {
			return minutes == 1 ? "1 minute" : minutes + " minutes";
		}
		return validFor.toString();
	}
}
