package io.lmctl.lmcode;
import java.nio.charset.StandardCharsets;
public final class Reports {
 private Reports(){}
 private static void printlnLf(String line){byte[] b=(line+"\n").getBytes(StandardCharsets.UTF_8);System.out.write(b,0,b.length);System.out.flush();}
 public static String escape(VmString s){return escape(s.toString());}
 public static String escape(String s){StringBuilder b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}
 public static int ok(String resultJson){printlnLf("{\"ok\": true, \"result\": "+resultJson+"}");return 0;}
 public static String errorJson(String phase,VmException e){return "{\"ok\": false, \"phase\": "+escape(phase)+", \"code\": "+escape(e.code)+", \"message\": "+escape(e.getMessage())+(e.line==null?"":", \"line\": "+e.line+(e.column==null?"":", \"column\": "+e.column))+"}";}
 public static int error(String phase,VmException e){printlnLf(errorJson(phase,e));return e.code.equals("APP_FAIL")?4:3;}
 public static int appFailure(String message){return error("app",new VmException("APP_FAIL",message));}
}
