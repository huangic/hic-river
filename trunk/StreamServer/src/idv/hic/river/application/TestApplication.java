package idv.hic.river.application;

import org.red5.server.adapter.ApplicationAdapter;
import org.red5.server.api.IConnection;
import org.red5.server.api.IScope;
import org.red5.server.api.Red5;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.stream.ClientBroadcastStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class TestApplication extends ApplicationAdapter {

	
	
	public String testApplication(){
		//log.debug("Application testApplication");
		//log.debug("Scope:{}",Red5.getConnectionLocal().getClient());
		
		
		//呼叫Client 做測試
		IConnection conn=Red5.getConnectionLocal();
		
		if (conn instanceof IServiceCapableConnection) { 
	        IServiceCapableConnection sc = (IServiceCapableConnection) conn; 
	        sc.invoke("ServerCall",new IPendingServiceCallback() {
				
				@Override
				public void resultReceived(IPendingServiceCall call) {
					// TODO Auto-generated method stub
					Object obj=call.getResult();
					
					log.debug("GetClientResult:{}",obj);
				}
			}); 
	   } 
		
		
		
		
		
		
		return "Client Call Server Fuinction Success!!";
		
	}
	
	

	public String testScope(){
		
		log.debug("Scope:{}",this.getScope().getName());
		
		
		//呼叫Client 做測試
			
		
		return "Client Call Server Fuinction Success!!";
		
	}
	
	
	
	
	@Override
	public void streamPublishStart(IBroadcastStream stream) {
		super.streamPublishStart(stream);
		log.debug("Publish start called, name: {}", stream.getName());
	    try{
	    stream.saveAs(stream.getPublishedName()+".flv", true);
	   
	    }catch(Exception ex){
	    	
	    	log.debug(ex.getMessage());
	    }
	   
	}
	
	@Override
	 public void streamBroadcastClose(IBroadcastStream stream) {
	  super.streamBroadcastClose(stream);
	  ClientBroadcastStream mystream = (ClientBroadcastStream) stream;
	  mystream.stopRecording();
	 }


	@Override
	public boolean appConnect(IConnection conn, Object[] params) {
		// TODO Auto-generated method stub
		return super.appConnect(conn, params);
	}
	
	
	
}
