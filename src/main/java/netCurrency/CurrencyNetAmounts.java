package netCurrency;
/**
 * @description:
 * @author: simon Huang
 * @time: 2022/8/16 18:58
 */
import io.muserver.*;
import io.muserver.handlers.ResourceHandlerBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.muserver.MuServerBuilder.httpServer;


public class CurrencyNetAmounts {
       //store the net amounts results,<currencyCode,amount>,assume the amounts are Integer
       private static ConcurrentHashMap<String,Integer> netAmountMap = new ConcurrentHashMap<String,Integer>();
       private boolean quitSign = false;

       //handle the input from command line or file,main thread
       public static void main(String[] main){
           Character inputWay=0;
           String quitSign;
           String regex = "^[A-Z]+\\x20-?[1-9]\\d*";
           Scanner scan = new Scanner(System.in);

           ExecutorService exec = Executors.newCachedThreadPool();
           //start http request/response thread
           exec.execute(new HttpEndpoint());
           //start the sse thread
           exec.execute(new ServerSentEventsEndpoint());
           //start the output per minute thread
           exec.execute(new perMinuteOutput());
           while(!inputWay.equals('y')&&!inputWay.equals('n')){
               System.out.println("please choose whether input from file(y/n):/n " +
                       "  y for yes, n for no! " +
                       "exit the system please type quit");
               quitSign = scan.nextLine();
               if(quitSign.equals("quit")){
                   System.out.println("system quit normally!");
                   exec.shutdown();
                   System.exit(0);
               }
               if(quitSign.length()==0){
                   inputWay = 'A';
               }else{
                   inputWay = quitSign.charAt(0);
               }
           }
           if(inputWay.equals('y')){
               System.out.println("please input the file path end with enter！");
               String filePath = scan.nextLine();
               if(filePath.equals("quit")){
                   System.out.println("system quit during choose the input way by quit instruction!");
                   exec.shutdown();
                   System.exit(0);
               }
               File dataFile = new File(filePath);
               while(!dataFile.exists()){
                   System.out.println("The file is not exit! please check the path and enter again:");
                   filePath = scan.nextLine();
                   if(filePath.equals("quit")){
                       System.out.println("system quit during input the file name by quit instruction!");
                       exec.shutdown();
                       System.exit(0);
                   }
                   dataFile = new File(filePath);
               }
               try {
                   FileInputStream fis = new FileInputStream(dataFile);
                   BufferedReader br = new BufferedReader(new InputStreamReader(fis));
                   String currencyline = null;
                   while ((currencyline = br.readLine()) != null) {
                       if(!currencyline.matches(regex)){
                           System.out.println("\""+currencyline+"\""+" illegal format! Ignored!");
                       }else{
                           String[] curSplit = currencyline.split("\\x20");
                           if(!CurrencyNetAmounts.netAmountMap.containsKey(curSplit[0])){
                               CurrencyNetAmounts.netAmountMap.put(curSplit[0],Integer.valueOf(curSplit[1]));
                           }else{
                               CurrencyNetAmounts.netAmountMap.put(curSplit[0],netAmountMap.get(curSplit[0])+Integer.valueOf(curSplit[1]));
                           }
                       }
                   }
                   br.close();
               }catch(Exception e){
                   e.printStackTrace();
                   System.out.println("system quit during process the file I/O!");
                   System.exit(1);
               }
           }
           //default input from command line or continue input from command line
           System.out.println("please continue to input the code amount in command line:");
           String curString = scan.nextLine();
           while(!curString.equals("quit")){
               if(!curString.matches(regex)){
                   System.out.println("\""+curString+"\""+" illegal format! Ignored!");
               }else{
                   String[] curSplit = curString.split("\\x20");
                   if (!CurrencyNetAmounts.netAmountMap.containsKey(curSplit[0])) {
                       CurrencyNetAmounts.netAmountMap.put(curSplit[0], Integer.valueOf(curSplit[1]));
                   } else {
                       CurrencyNetAmounts.netAmountMap.put(curSplit[0], netAmountMap.get(curSplit[0]) + Integer.valueOf(curSplit[1]));
                   }
               }
               curString = scan.nextLine();
           }
           System.out.println("system quit during process command line input by quit instruction!");
           exec.shutdown();
           System.exit(0);
       }

       //once per minute, output the net amounts, exclude the zero value;
       static class perMinuteOutput implements Runnable{

           @Override
           public void run() {
               try {
                   while(true) {
                       SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-hh:mm:00");
                       Date date = new Date();
                       String curTime = simpleDateFormat.format(date);
                       System.out.println("The net amounts in " + curTime + " are:");
                       for (Map.Entry<String, Integer> entry : CurrencyNetAmounts.netAmountMap.entrySet()) {
                           if (entry.getValue().equals(0)) {
                               continue;
                           } else {
                               System.out.println(entry.getKey() + " " + entry.getValue());
                           }
                       }
                       Thread.sleep(60000);
                   }
               } catch (InterruptedException e) {
                   e.printStackTrace();
               }
           }
       }

       //endpoints 1 sse
       static class ServerSentEventsEndpoint implements Runnable{

           public void publishMessage(SsePublisher publisher) {
               try{
                     while(true) {
                         for (Map.Entry<String, Integer> entry : CurrencyNetAmounts.netAmountMap.entrySet()) {
                             try {
                                 if (entry.getValue().equals(0)) {
                                     continue;
                                 } else {
                                     publisher.send(entry.getKey() + " " + entry.getValue());
                                 }
                             } catch (Exception e) {
                                 // The user has probably disconnected so stopping
                                 break;
                             }
                         }
                         Thread.sleep(1000);
                     }
               }catch (Exception e) {
                       // The user has probably disconnected so stopping
                       e.printStackTrace();
               }finally{
                   publisher.close();
               }
           }

           @Override
           public void run() {
               MuServer server = httpServer()
                       .addHandler(Method.GET, "/sse/counter", (request, response, pathParams) -> {
                           SsePublisher publisher = SsePublisher.start(request, response);
                           new Thread(() -> this.publishMessage(publisher)).start();
                       })
                       .addHandler(ResourceHandlerBuilder.fileOrClasspath("src/main/resources/samples", "/samples"))
                       .start();
               System.out.println("Open " + server.uri().resolve("/sse.html") + " to check result.");
           }
       }

    //endpoints 2 request/response
    static class HttpEndpoint implements Runnable {
        @Override
        public void run() {
            MuServer server = MuServerBuilder.httpServer()
                    .addHandler(Method.GET, "/search", (request, response, pathParams) -> {
                        String code = "";
                        code= request.query().get("sCode");
                        if(!netAmountMap.containsKey(code)){
                            response.write("null");
                        }else{
                            response.write(netAmountMap.get(code).toString());
                        }
                    })
                    .addHandler(ResourceHandlerBuilder.fileOrClasspath("src/main/resources/samples", "/samples"))
                    .start();
            System.out.println("Started HTTP server at " + server.uri().resolve("/requestResponse.html"));
        }
    }

}
