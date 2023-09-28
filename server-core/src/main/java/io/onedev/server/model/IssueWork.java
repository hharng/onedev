package io.onedev.server.model;

import io.onedev.server.util.Day;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.annotation.Nullable;
import javax.persistence.*;
import java.util.Date;

import static io.onedev.server.model.IssueWork.PROP_DATE;

@Entity
@Table(indexes={@Index(columnList="o_issue_id"), @Index(columnList="o_user_id"), @Index(columnList=PROP_DATE)})
@Cache(usage=CacheConcurrencyStrategy.READ_WRITE)
public class IssueWork extends AbstractEntity {

	private static final int MAX_CONTENT_LEN = 1000;
	
	private static final long serialVersionUID = 1L;
	
	public static final String PROP_DATE = "date";
	
	public static final String PROP_DAY = "day";
	
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(nullable=false)
	private Issue issue;
	
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(nullable=false)
	private User user;
	
	private Date date;
	
	private int day;
	
	private int minutes;

	@Column(length=MAX_CONTENT_LEN)
	private String note;

	public Issue getIssue() {
		return issue;
	}

	public void setIssue(Issue issue) {
		this.issue = issue;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public Date getDate() {
		return date;
	}

	public void setDate(Date date) {
		this.date = date;
		day = new Day(date).getValue();
	}

	public int getDay() {
		return day;
	}

	public int getMinutes() {
		return minutes;
	}

	public void setMinutes(int minutes) {
		this.minutes = minutes;
	}

	@Nullable
	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = StringUtils.abbreviate(note, MAX_CONTENT_LEN);		
	}
}
