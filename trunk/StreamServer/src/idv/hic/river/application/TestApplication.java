package idv.hic.river.application;

import org.red5.server.adapter.ApplicationAdapter;
import org.red5.server.api.IConnection;
import org.red5.server.api.IScope;
import org.red5.server.api.Red5;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.api.service.IServiceCapableConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class TestApplication extends ApplicationAdapter {

	
	
	public String testApplication(){
		//log.debug("Application testApplication");
		log.debug("Scpoe:%s",this.scope.getName());
		
		
		//呼叫Client 做測試
		IConnection conn=Red5.getConnectionLocal();
		
		if (conn instanceof IServiceCapableConnection) { 
	        IServiceCapableConnection sc = (IServiceCapableConnection) conn; 
	        sc.invoke("ServerCall",new IPendingServiceCallback() {
				
				@Override
				public void resultReceived(IPendingServiceCall call) {
					// TODO Auto-generated method stub
					Object obj=call.getResult();
					
					log.debug("GetClientResult:%s",obj);
				}
			}); 
	    } 
		
		
		
		return "SHIT!!!";
		
	}
	
	
	
	@Override
	public synchronized boolean connect(IConnection conn, IScope scope,
			Object[] params) {
		// TODO Auto-generated method stub
		
		log.debug("Application Connedted");
		
		return super.connect(conn, scope, params);
	}

	@Override
	public synchronized void disconnect(IConnection conn, IScope scope) {
		// TODO Auto-generated method stub
		super.disconnect(conn, scope);
	}

	@Override
	public synchronized boolean start(IScope scope) {
		// TODO Auto-generated method stub
		return super.start(scope);
	}

	@Override
	public synchronized void stop(IScope scope) {
		// TODO Auto-generated method stub
		super.stop(scope);
	}
	
	
}
