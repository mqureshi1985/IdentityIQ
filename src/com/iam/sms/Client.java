package com.iam.sms;

/**
 * 
 * SMSGlobal Client
 */
public class Client {
  private HttpTransport HttpTransport;

  public Client(HttpTransport HttpTransport) {
    this.HttpTransport = HttpTransport;
  }

  public String sendMessage(Message message) throws Exception {
    return HttpTransport.sendMessage(message);
  }
}