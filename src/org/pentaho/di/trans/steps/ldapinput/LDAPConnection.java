/*************************************************************************************** 
 * Copyright (C) 2007 Samatar.  All rights reserved. 
 * This software was developed by Samatar and is provided under the terms 
 * of the GNU Lesser General Public License, Version 2.1. You may not use 
 * this file except in compliance with the license. A copy of the license, 
 * is included with the binaries and source code. The Original Code is Samatar.  
 * The Initial Developer is Samatar.
 *
 * Software distributed under the GNU Lesser Public License is distributed on an 
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. 
 * Please refer to the license for the specific language governing your rights 
 * and limitations.
 ***************************************************************************************/

package org.pentaho.di.trans.steps.ldapinput;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.naming.Context;
import javax.naming.NameClassPair;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.i18n.BaseMessages;

public class LDAPConnection {
	private static Class<?> PKG = LDAPInputMeta.class; // for i18n purposes, needed by Translator2!!   $NON-NLS-1$
	
	public static final int DEFAULT_PORT = 389;
	private static final String DEFAUL_FILTER_STRING="objectclass=*";
	
	public static final int STATUS_SKIPPED=  0;
	public static final int STATUS_INSERTED= 1;
	public static final int STATUS_UPDATED= 2;
	public static final int STATUS_DELETED= 3;
	public static final int STATUS_ADDED= 4;
	
	private LogChannelInterface	log;
	
	private String hostName;
	private int port;
	private String username;
	
	private String searchBase;
	private String filter;
	private Hashtable<String, String> env;
	
    private InitialLdapContext ctx;
    private SearchControls controls;
    private int timeLimit;
    private int pagingSize;
    private NamingEnumeration<SearchResult> results;
    
    private String referral;
    private String derefAliases;

	
	/**
	 * Construct a new LDAP Connection
	 */
	public LDAPConnection(LogChannelInterface logInterface, String hostName, int port) throws KettleException {
		this.log = logInterface;
		this.port =port;
		this.hostName=hostName;
		this.env = new Hashtable<String, String>();
		this.referral="follow";
		this.derefAliases="always";
	}
	
	/**
    *  Connect to LDAP server
    *  @throws KettleException
    */
	public void connect() throws KettleException{
		connect(null, null);
	}
	
	private Hashtable<String, String> getEnv() {
		return this.env;
	}
	
   /**
    *  Connect to LDAP server
    *  @param username : username
    *  @param password : password
    *  @throws KettleException
    */
	public void connect(String username, String password) throws KettleException{

		getEnv().put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
		getEnv().put("java.naming.ldap.derefAliases", getDerefAliases());
		getEnv().put(Context.REFERRAL, getReferral());

		if(getHostName().indexOf("ldap://")>=0)
			getEnv().put(Context.PROVIDER_URL,getHostName() + ":" + getPort());
        else
        	getEnv().put(Context.PROVIDER_URL, "ldap://"+getHostName() + ":" + getPort());
		getEnv().put(Context.SECURITY_AUTHENTICATION, "simple");
        
        if (!Const.isEmpty(username)) {
    		this.username=username;
    		getEnv().put(Context.SECURITY_PRINCIPAL, username);
    		getEnv().put(Context.SECURITY_CREDENTIALS, password); 
        }

	    try {
	    	this.ctx=new InitialLdapContext(getEnv(), null);
	        if (getInitialContext()==null) {
			   throw new KettleException(BaseMessages.getString(PKG, "LDAPInput.Error.UnableToConnectToServer"));
			}
		    if(log.isBasic()) log.logBasic(BaseMessages.getString(PKG, "LDAPInput.Log.ConnectedToServer",getHostName(),Const.NVL(getUserName(), "")));
			if(log.isDetailed()) log.logDetailed(BaseMessages.getString(PKG, "LDAPInput.ClassUsed.Message",getInitialContext().getClass().getName()));
			   
	    }catch(Exception e) {
	    	 throw new KettleException(BaseMessages.getString(PKG, "LDAPinput.Exception.ErrorConnecting", e.getMessage()));
	    }
	}
	

	private void setFilter(String filter) {
		this.filter=filter;
	}
	private String getFilter() {
		return this.filter;
	}
	private void setSearchBase(String searchBase) {
		this.searchBase=searchBase;
	}
	private String getSearchBase() {
		return this.searchBase;
	}
	public void setTimeLimit(int timeLimit) {
		this.timeLimit=timeLimit;
	}
	public int getTimeLimit() {
		return this.timeLimit;
	}

	public void SetPagingSize(int value) {
		this.pagingSize= value;
	}
	private int GetPagingSize() {
		return this.pagingSize;
	}
	private boolean isPagingUsed() {
		return (GetPagingSize()>0);
	}
	public void setReferral(String value) {
		this.referral=value;
	}
	public String getReferral() {
		return this.referral;
	}
	public void setDerefAliases(String value) {
		this.derefAliases=value;
	}
	public String getDerefAliases() {
		return this.derefAliases;
	}
	public void search(String searchBase, String filter, int limitRows, String[] attributeReturned) throws KettleException {
		//Set the Search base.This is the place where the search will
		setSearchBase(searchBase);
		setFilter(Const.NVL(correctFilter(filter), DEFAUL_FILTER_STRING));
		 try {
			 
			 if(Const.isEmpty(getSearchBase()))  {
				 // get Search Base
				 Attributes attrs = getInitialContext().getAttributes("", new String[] { "namingContexts" });
				 Attribute attr = attrs.get("namingContexts");
				  
				 setSearchBase(attr.get().toString());
				 if(log.isDetailed()) log.logDetailed(BaseMessages.getString(PKG, "LDAPInput.SearchBaseFound",getSearchBase()) );
			 } 
			  
			 this.controls = new SearchControls();
			 if(limitRows>0) this.controls.setCountLimit(limitRows);
			    
			 // Time Limit
			 if(getTimeLimit()>0) this.controls.setTimeLimit(getTimeLimit() * 1000);

			 //Specify the attributes to return
			 if(attributeReturned!=null) this.controls.setReturningAttributes(attributeReturned);

		     //Specify the search scope
		     this.controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
	         
	         //Set the page size?
	         if(isPagingUsed()) {
        		// paging is activated
        		//Request the paged results control
    			Control[] ctls = new Control[]{new PagedResultsControl(GetPagingSize(),true)};
    			getInitialContext().setRequestControls(ctls);

        		if(log.isDebug()) log.logDebug(BaseMessages.getString("LDAPInput.Log.PageSize", String.valueOf(GetPagingSize())) );
	         }
	         //Search for objects using the filter
	         this.results = getInitialContext().search(getSearchBase(), getFilter(), getSearchControls());
	        
		 }catch(Exception e){
			 throw new KettleException(BaseMessages.getString("LDAPConnection.Error.Search"), e);
		 }
	 }
	public void addBinaryAttribute(String name) {
		this.env.put("java.naming.ldap.attributes.binary", name);
	}
	
   
	
	public int delete(String dn, boolean checkEntry) throws KettleException {
		try {
			
			if(checkEntry) {
				// First Check entry
				getInitialContext().lookup(dn);
			}
			// The entry exists
			getInitialContext().destroySubcontext(dn);
			if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "LDAPinput.Exception.Deleted", dn));
			return STATUS_DELETED;
		}catch(NameNotFoundException n) {
			// The entry is not found
			if(checkEntry) {
				throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Deleting.NameNotFound", dn), n);
			}
			return STATUS_SKIPPED;
		}catch(Exception e) {
			throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Delete", dn), e);
		}
	}
	
	public int update(String dn, String[] attributes, String[] values, boolean checkEntry) throws KettleException {
		try {
			int nrAttributes= attributes.length;
		    ModificationItem[] mods = new ModificationItem[nrAttributes];
		    for(int i=0; i<nrAttributes; i++) {
		    	// Define attribute
		    	Attribute mod = new BasicAttribute(attributes[i], values[i]);
		    	if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "LDAPConnection.Update.Attribute", attributes[i], values[i]));
		    	// Save update action on attribute
		    	mods[i] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE, mod);
		    }
		    // We have all requested attribute
		    // let's update now
		    getInitialContext().modifyAttributes(dn, mods);
			return STATUS_UPDATED;
		}catch(NameNotFoundException n) {
			// The entry is not found
			if(checkEntry) {
				throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Deleting.NameNotFound", dn), n);
			}
			return STATUS_SKIPPED;
		}catch(Exception e) {
			throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Update", dn), e);
		}
	}

	public int add(String dn, String[] attributes, String[] values, String multValuedSeparator, boolean checkEntry) throws KettleException {
		try {
			Attributes attrs = buildAttributes(dn, attributes, values, multValuedSeparator);
		    // We had all attributes
			getInitialContext().modifyAttributes(dn,DirContext.ADD_ATTRIBUTE,attrs);
			return 	STATUS_ADDED;
		}catch(NameNotFoundException n) {
			// The entry is not found
			if(checkEntry) {
				throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Deleting.NameNotFound", dn), n);
			}
			return STATUS_SKIPPED;
		}catch(Exception e) {
			throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Add", dn), e);
		}
	}
	
	   /**
	    *  Insert record in LDAP based on DN
	    *  @param dn : Distinguished Name (Key for lookup)
	    *  @param attributes : contains all the attributes to set for insert
	    *  @param values : contains all the values for attributes
	    *  @param multValuedSeparator : multi-valued attributes separator
	    * @throws KettleException
	    */
	public void insert(String dn, String[] attributes, String[] values, String multValuedSeparator) throws KettleException {
		try {
			
			Attributes attrs = buildAttributes(dn, attributes, values, multValuedSeparator);
			// We had all attributes
			// Let's insert now
			getInitialContext().createSubcontext(dn, attrs);
			
		}catch(Exception e) {
			throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Insert", dn), e);
		}
		
	}
	
   /**
    *  Upsert record in LDAP
    *  First we will check if the entry exist based on DN
    *  If we can not find it, we will create it
    *  otherwise, we will perform an update
    *  @param dn : Distinguished Name (Key for lookup)
    *  @param attributes : contains all the attributes to set for insert
    *  @param values : contains all the values for attributes
    *  @param attributesToUpdate : contains attributes to update
    *  @param valuesToUpdate : contains values for attributes to update
    *  @param multValuedSeparator : multi-valued attributes separator
    *  @return status : STATUS_INSERTED, STATUS_UPDATED or STATUS_SKIPPED
    * @throws KettleException
    */
	public int upsert(String dn, String[] attributes, String[] values,
			String[] attributesToUpdate, String[] valuesToUpdate,String multValuedSeparator) throws KettleException {
		
		try {
		    
		    boolean found = false;
			try {
				getInitialContext().getAttributes(dn);
				found = true;
			} catch (NameNotFoundException n) {
				Attributes attrs = buildAttributes(dn, attributes, values, multValuedSeparator);
				getInitialContext().createSubcontext(dn, attrs);
				return STATUS_INSERTED;
			}

			if (found && attributesToUpdate!=null && attributesToUpdate.length>0) {
				// The entry already exist
				// let's update
				Attributes attrs = buildAttributes(dn, attributesToUpdate, valuesToUpdate, multValuedSeparator);
				getInitialContext().modifyAttributes(dn, DirContext.REPLACE_ATTRIBUTE, attrs);
				return STATUS_UPDATED;
			}
	    
		}catch(Exception e) {
			throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Upsert", dn), e);
		}
		return STATUS_SKIPPED;
	}
	private Attributes buildAttributes(String dn, String[] attributes, String[] values, String multValuedSeparator){
		Attributes attrs = new javax.naming.directory.BasicAttributes(true);
		int nrAttributes= attributes.length;
	    for(int i=0; i<nrAttributes; i++) {
	    	if(!Const.isEmpty(values[i])) {
	    		// We have a value
	    		String value = values[i].trim();
	    		if (multValuedSeparator!=null && value.indexOf(multValuedSeparator) > 0) {
					Attribute attr = new javax.naming.directory.BasicAttribute(attributes[i]);
					for (String attribute : value.split(multValuedSeparator)) {
						attr.add(attribute);
					}
					attrs.put(attr);
		    	}else {
		    		attrs.put(attributes[i],value);
		    	}
	    	}
	    }
	    return attrs;
	}
	
   /**
    *  Rename an entry
    *  @param oldDn Distinguished name of the entry to rename
    *  @param newDn target Distinguished name (new)
    *  @param deleteRDN To specify whether you want to keep the old name attribute when you use rename entry
    *  		true : do not keep the old value (defaut)
    *  		false : keep the old value as an attribute
    *  @throws KettleException
    */
	public void rename(String oldDn, String newDn, boolean deleteRDN)  throws KettleException {
		try {
			if(!deleteRDN) {
				// Keep the old dn as attribute
				getInitialContext().removeFromEnvironment("java.naming.ldap.deleteRDN");
			}
			Map<String, Attributes> childs = new java.util.HashMap<String, Attributes>();
			List<String> paths = new ArrayList<String>();
	
			list(oldDn, childs, paths);
			
			// Destroy sub contexts
			for (String childName : paths) {
				getInitialContext().destroySubcontext(childName);
			}

			// Rename entry
			try {
				getInitialContext().rename(oldDn, newDn);
			} catch (Exception e) {
				// something goes wrong
				// re attached removed sub contexts
				for (int i = paths.size(); i > 0; i--) {
					getInitialContext().createSubcontext(paths.get(i - 1),childs.get(paths.get(i - 1)));
				}
				throw e;
			}

			// attach sub context
			List<String> newpaths = new ArrayList<String>();
			for (String childName : paths) {
				newpaths.add(childName.replaceAll(oldDn, newDn));
			}

			for (int i = newpaths.size(); i > 0; i--) {
				getInitialContext().createSubcontext(newpaths.get(i - 1),childs.get(paths.get(i - 1)));
			}

		}catch(Exception e) {
			throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.Renaming",oldDn, newDn), e);
		}finally {
			try {
				if(!deleteRDN) {
					// Delete the old dn as attribute
					// switch back to default value
					getInitialContext().addToEnvironment("java.naming.ldap.deleteRDN", "true");
				}
			}catch(Exception e){}
		}
		
	}
	@SuppressWarnings("rawtypes")
	private void list(String rootName, Map<String, Attributes> childs, List<String> paths) throws Exception {
		NamingEnumeration ne = getInitialContext().list(rootName);
		while (ne.hasMore()) {
			NameClassPair nameCP = (NameClassPair) ne.next();
			childs.put(nameCP.getName() + "," + rootName,getInitialContext().getAttributes(nameCP.getName()+ "," + rootName));
			list(nameCP.getName() + "," + rootName, childs, paths);
			paths.add(nameCP.getName() + "," + rootName);
		}
	}
   /**
    *  Close the LDAP connection
    * @throws KettleException
    */
	public void close() throws KettleException {
		if(getInitialContext()!=null) {
			try {
				this.ctx.close();
				if(getSearchResult()!=null) this.results=null;
				if(log.isBasic()) log.logBasic(BaseMessages.getString(PKG, "LDAPInput.log.Disconnection.Done"));
			} catch (Exception e){
	             log.logError(BaseMessages.getString(PKG, "LDAPInput.Exception.ErrorDisconecting",e.toString()));
	             log.logError(Const.getStackTracker(e));
			}
		}
	}
	
	public Attributes getAttributes() throws KettleException {
		
	    byte[] cookie=null;
		while (!getSearchResult().hasMoreElements()) {
			if(isPagingUsed()){
				// we are using paging...
				// we need here to check the response controls
				// and pass back cookie to next page
				try {
					// examine response controls
					Control[] rc = getInitialContext().getResponseControls();
					if (rc != null) {
						for (int i = 0; i < rc.length; i++) {
							if (rc[i] instanceof PagedResultsResponseControl) {
								PagedResultsResponseControl prc = (PagedResultsResponseControl) rc[i];
								cookie = prc.getCookie();
							}
						}
					}
					// pass the cookie back for the next page
					getInitialContext().setRequestControls(new javax.naming.ldap.Control[] 
					        { new PagedResultsControl(GetPagingSize(), cookie,Control.CRITICAL) });
						
					 if ((cookie != null) && (cookie.length != 0)) {
						 // get search result for the page
						 this.results = getInitialContext().search(getSearchBase(), getFilter(), getSearchControls());
					 }else {
						 return null;
					 }
				 
				}catch(Exception e) {
					throw new KettleException(BaseMessages.getString(PKG, "LDAPInput.Exception.ErrorPaging"), e);
				}
			 
				while (!getSearchResult().hasMoreElements()) {
				   return null;
				}
			}else {
				// User do not want to use paging
				// we have already returned all the result
				return null;
			}
		 }

		try {
			SearchResult searchResult = (SearchResult) getSearchResult().next();
			return searchResult.getAttributes();   
		}catch(Exception e) {
			throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Exception.GettingAttributes"), e);
		}
	}
	
	private String getHostName() {
		return this.hostName;
	}
	
	private int getPort() {
		return this.port;
	}
	private String getUserName() {
		return this.username;
	}
	
	private InitialLdapContext getInitialContext() {
		return this.ctx;
	}
	
	private SearchControls getSearchControls() {
		return this.controls;
	}
	
	private NamingEnumeration<SearchResult> getSearchResult() {
		return this.results;
	}
    /**
     * Remove CR and LF from filter string
     * @param filter
     * @return corrected filter
     */
	private static String correctFilter(String filter) {
		return Const.isEmpty(filter)?"":filter.replaceAll("(\\r|\\n)", "");
	}
	public static String extractBytesAndConvertToString(Attribute attr, boolean isSID) throws Exception {
		byte[] b;
		try {
			b= (byte[])(byte[]) attr.get();
		}catch(Exception e) {
			// Get bytes from String
			b =  attr.get().toString().getBytes();
		}
		if(isSID) return getSIDAsString(b);
		else return byteToHexEncode( b);
	}

   /**
    *  Convert the SID into string format
    * @param SID
    * @return String representation of SID
    */
	private static String getSIDAsString(byte[] SID) {
		long version;
		long authority;
		long count;
		long rid;
		String strSID;
		strSID = "S";
		version = SID[0];
		strSID = strSID + "-" + Long.toString(version);
		authority = SID[4];
		for (int i = 0;i<4;i++) {
			authority <<= 8;
			authority += SID[4+i] & 0xFF;
		}
		strSID = strSID + "-" + Long.toString(authority);
		count = SID[2];
		count <<= 8;
		count += SID[1] & 0xFF;
		for (int j=0;j<count;j++) {
			rid = SID[11 + (j*4)] & 0xFF;
			for (int k=1;k<4;k++) {
				rid <<= 8;
				rid += SID[11-k + (j*4)] & 0xFF;
			}
			strSID = strSID + "-" + Long.toString(rid);
		}
		return strSID;
	}
	
	/**
	* Converts the GUID to a readable string format
	* @param inArr
	* @return the formatted GUID
	*/
	private static String byteToHexEncode ( byte [] inArr )
	{
		StringBuffer guid = new StringBuffer ();
		for ( int i = 0; i < inArr.length; i++ ) {
			StringBuffer dblByte = new StringBuffer ( Integer.toHexString ( inArr [ i ] & 0xff ) );
			if ( dblByte.length () == 1 ) {
				guid.append ( "0" );
			}
			guid.append ( dblByte );
		}
		return guid.toString ();
	}

	public RowMeta getFields(String searchBase) throws KettleException {
				
		try {
			search(searchBase, null, 0, null);
			Attributes attributes = getAttributes();
	
	        NamingEnumeration<? extends Attribute> ne = attributes.getAll();
	        RowMeta fields = new RowMeta();

	        Attribute attr = null;
	        
	        while (ne.hasMore()) {
	        	attr = ne.next();
	    		// Get fieldname
			    ValueMetaInterface value = new ValueMeta(attr.getID());
	
	            String attributeValue=attr.getID();
	            // Try to get the Type
	            if(IsDate(attributeValue)){
	            	value.setType(ValueMeta.TYPE_DATE);
	    		} else if(IsInteger(attributeValue)){
	    			value.setType(ValueMeta.TYPE_INTEGER);
	    		} else if(IsNumber(attributeValue)){
	    			value.setType(ValueMeta.TYPE_NUMBER);
	    		} else {
	    			value.setType(ValueMeta.TYPE_STRING);		            
	            }  
	            fields.addValueMeta(value);
	        }
	        
	        return fields;
		}catch(Exception e){
			throw new KettleException(BaseMessages.getString(PKG, "LDAPConnection.Error.RetrievingFields"));
		}
	}
	private boolean IsNumber(String str){
		  try {
		     Float.parseFloat(str);
		  } catch(Exception e)   {return false; }
		  return true;
	}
	
	private boolean IsDate(String str){
		  // TODO: What about other dates? Maybe something for a CRQ
		  try  {
		        SimpleDateFormat fdate = new SimpleDateFormat("yy-mm-dd");
		        fdate.parse(str);
		  } catch(Exception e)   {return false; }
		  return true;
	}
	private boolean IsInteger(String str) {
		  try  {
		    Integer.parseInt(str);
		  } catch(NumberFormatException e)   {return false; }
		  return true;
	}
}