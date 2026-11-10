package com.drift.backend.account.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.drift.backend.email.MailLinks;

@Configuration
public class InvitationMailConfig {

	@Bean
	public InvitationMailer smtpInvitationMailer(JavaMailSender mailSender, @Value("${app.invitation.from}") String from) {
		return new SmtpInvitationMailer(mailSender, from);
	}

	public static String link(String linkBaseUrl, String code) {
		return MailLinks.normalize(linkBaseUrl) + "#invite=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
	}

	static final class SmtpInvitationMailer implements InvitationMailer {

		private final JavaMailSender mailSender;
		private final String from;

		SmtpInvitationMailer(JavaMailSender mailSender, String from) {
			this.mailSender = mailSender;
			this.from = from;
		}

		@Override
		public void sendInvitation(String email, String link, String code, String organisationName, String roleLabel,
				Duration validFor) {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(from);
			message.setTo(email);
			message.setSubject("You are invited to DRIFT");
			message.setText("""
					You are invited to join %s on DRIFT as a %s.
					Open this link, or enter the invitation code on the signup page. It expires in %s.

					%s

					Invitation code:
					%s

					If you were not expecting this, you can ignore this email.
					""".formatted(organisationName, roleLabel, describe(validFor), link, code));
			mailSender.send(message);
		}

		private String describe(Duration validFor) {
			long days = validFor.toDays();
			if (days > 0 && validFor.equals(Duration.ofDays(days))) {
				return days == 1 ? "1 day" : days + " days";
			}
			return validFor.toString();
		}
	}
}
