/* 
 * =============================================================
 * Copyright (C) 2007-2011 Edgenius (http://www.edgenius.com)
 * =============================================================
 * License Information: http://www.edgenius.com/licensing/edgenius/2.0/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2.0
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 * http://www.gnu.org/licenses/gpl.txt
 *  
 * ****************************************************************
 */
package com.edgenius.wiki.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.allen_sauer.gwt.log.client.Log;
import com.edgenius.core.Constants;
import com.edgenius.core.util.AuditLogger;
import com.edgenius.core.util.FileUtil;
import com.edgenius.core.util.FileUtilException;
import com.edgenius.wiki.model.AbstractPage.PAGE_TYPE;
import com.thoughtworks.xstream.XStream;

/**
 * @author Dapeng.Ni
 */
public class DataBinder {

	public static final String HOME_PAGE_BINDER_NAME = "com.edgenius.wiki.HomepageBinder";
	public static final String USER_FOLLOWING_BINDER_NAME = "com.edgenius.wiki.UserFollowingBinder";

	private String version;
	
	private List<DataObject> objects = new ArrayList<DataObject>();
	private int options;
	//key: download options, relative dir in zip file
	private Map<Integer,String> dirs = new HashMap<Integer, String>(); 
	
	public int getObjectsSize(){
		return objects.size();
	}
	public int getOptions() {
		return options;
	}
	public void setOptions(int options) {
		this.options = options;
	}
	
	public String getVersion() {
		return version;
	}
	public void setVersion(String version) {
		this.version = version;
	}
	/**
	 * @param list
	 */
	public void addAll(String name, List<? extends Object> list) {
		DataObject exist = findObject(name);
		if(exist == null){
			exist = new DataObject(name,list); 
			objects.add(exist);
		}else{
			exist.addAll(list);
		}
	}
	public void add(String name, Object obj) {
		DataObject exist = findObject(name);
		if(exist == null){
			exist = new DataObject(name,obj); 
			objects.add(exist);
		}else{
			exist.add(obj);
		}
		
	}

	public List<? extends Object> get(String name) {
		DataObject obj = findObject(name);
		if(obj == null)
			return null;
		
		return obj.getList();
	}

	/**
	 * Get back relative directory in backup zip, for example option BackService.BACKUP_ATTACHMENT returns /data/repository
	 */
	public String getDir(int option) {
		return dirs.get(option);
	}
	
	public void addDir(int option,String dir){
		dirs.put(option, dir);
	}
	
	
	private DataObject findObject(String name) {
		for (DataObject obj : objects) {
			if(obj.getName().equals(name))
				return obj; 
		}
		
		return null;
		
	}
	
	//********************************************************************
	//               Private classes
	//********************************************************************
	public static class DataObject{
		private String name;
		private List<Object> list = new ArrayList<Object>();

		public DataObject() {}
		
		public DataObject(String name, List<? extends Object> list) {
			this.name = name;
			this.list.addAll(list);
		}
		public DataObject(String name, Object obj) {
			this.name = name;
			this.list.add(obj);
		}

		public void addAll(List<? extends Object> list) {
			this.list.addAll(list);
		}

		public void add(Object obj) {
			this.list.add(obj);
		}

		public String getName() {
			return this.name;
		}
		public List<? extends Object> getList() {
			return list;
		}
	
		
		public boolean equals(Object obj){
			if(!(obj instanceof DataObject))
				return false;
			//very simple check, ignore pre-check
			return this.name.equals(((DataObject)obj).getName());
		}
		public int hashCode(){
			return new HashCodeBuilder().append(this.name).toHashCode();
		}
	}
	//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// As page content is most large part in exported binder. It may cause out-of-memory execption if
	// put page content together with others object. So here export content to 2 parts:
	// ContentMap - mapping contentID and its body-files name
	// ContnetBody - the real page content text, it will split into small files(body-file) base one its size. 
	public static class ContentBodyMap{
		private static final Logger log = LoggerFactory.getLogger(DataBinder.class);
		
		private transient static final String BACKUP_CONTENT_MAPFILE = "content-map.xml";
		private transient static final String BACKUP_DIR_SUFFIX = "_backcontent";
		private transient static final String BACKUP_CONTENT_FILE_PREFIX = "content";
		
		private transient static XStream xstream = new XStream();
		
		private transient String rootPath;
		private transient String canonialRootPath;
		//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		//variables for backup  
		private transient int fileIndex = 0;
		private transient Map<File, String> fileMap = new HashMap<File, String>();
		//transient: XML won't serialize it here together with above maps 
		private transient ContentBodys bodys = new ContentBodys();
		//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		//variables for restore 		
		//load from content body file when importing 
		//<fileIndex,contentBodys>
		private transient Map<Integer, ContentBodys> bodyMap;
		
		//<contentId, fileIndex>
		private Map<Integer, Integer> pageMap = new HashMap<Integer, Integer>();
		private Map<Integer, Integer> draftMap = new HashMap<Integer, Integer>();
		private Map<Integer, Integer> historyMap = new HashMap<Integer, Integer>();
		
		//default constructor for XStream
		public ContentBodyMap(){};
		
		/**
		 * Call by backup (export) service and will create a temporarily directory to cache the page content files.
		 * After all page, draft, history content are processed, call finialise() and getZipMap() to put these file into 
		 * backup zip file list.
		 * 
		 * @throws FileUtilException
		 * @throws IOException
		 */
		public static ContentBodyMap initialForBackup() throws FileUtilException, IOException{
			ContentBodyMap obj = new ContentBodyMap();
			obj.rootPath = FileUtil.createTempDirectory(BACKUP_DIR_SUFFIX);
			obj.canonialRootPath  = new File(obj.rootPath).getCanonicalPath();
			
			return obj;
			
		}
		/**
		 * Call by restore (import) service and will assume the content body files is already unzip to given rootPath. 
		 * @param rootPath
		 * @return 
		 * @throws FileNotFoundException 
		 */
		public static ContentBodyMap initialForRestore(String rootPath) throws FileNotFoundException {
			File file = new File(rootPath, BACKUP_CONTENT_MAPFILE);
			FileInputStream fos = new FileInputStream(file);
			ContentBodyMap obj = (ContentBodyMap) xstream.fromXML(fos);
			obj.rootPath = rootPath;
			IOUtils.closeQuietly(fos);
			
			//must initialise here rather than in class level
			obj.bodyMap = new HashMap<Integer, ContentBodys>();
			
			return obj;
		}
		public void add(Integer contentId, PAGE_TYPE type, String body) throws IOException{
			boolean full = bodys.add(contentId, type, body);
			switch (type) {
			case PAGE:
				pageMap.put(contentId, fileIndex);
				break;
			case DRAFT:
				draftMap.put(contentId, fileIndex);
				break;
			case HISTORY:
				historyMap.put(contentId, fileIndex);
				break;
			}
			
			//if current contentBodys is full, then save and clear it.
			if(full){
				//save body file
				String filename = writeFile(bodys, BACKUP_CONTENT_FILE_PREFIX+fileIndex + ".xml");
				log.info("Write content body file {} ", filename);
				//reset
				bodys.clear();
				fileIndex++;
			}
		}
		
		
		public void finalise() throws IOException {
			//save body file if it doesn't save yet.
			File file = new File(rootPath, BACKUP_CONTENT_FILE_PREFIX+fileIndex + ".xml");
			if(!fileMap.containsKey(file)){
				String filename = writeFile(bodys, BACKUP_CONTENT_FILE_PREFIX+fileIndex + ".xml");
				log.info("Write content body file {} ", filename);
				
				bodys.clear();
				bodys = null;
			}
			
			//save contentMap itself 
			String mapFilename = writeFile(this, BACKUP_CONTENT_MAPFILE);
			log.info("Write content body mapping file {} ", mapFilename);
		}
		
		public Map<File, String> getZipMap() {
			return fileMap;
		}
		
		/**
		 * Please note - for memory sake, same contentId can not be load more than once. 
		 * 
		 * @param contentId
		 * @param type
		 * @return
		 */
		public String getContent(Integer contentId, PAGE_TYPE type){
			Integer fIndex = null;
			switch (type) {
			case PAGE:
				fIndex = pageMap.get(contentId);
				break;
			case DRAFT:
				fIndex = draftMap.get(contentId);
				break;
			case HISTORY:
				fIndex = historyMap.get(contentId);
				break;
			}
			
			if(fIndex == null){
				//should here throw exception and stop all import process?
				AuditLogger.error("Restore can not find fileIndex for content ID " + contentId);
				return null;
			}
			
			//try load contentBodys from cached map - if not exist, then load from XML file
			ContentBodys contentBodys = bodyMap.get(fIndex);
		
			if(contentBodys == null){
				//load from XML file
				contentBodys = loadFile(BACKUP_CONTENT_FILE_PREFIX+fIndex + ".xml");
				if(contentBodys == null){
					AuditLogger.error("Restore can not load content body file " + fIndex + " for content ID " + contentId);
					return null;
				}
				
				bodyMap.put(fIndex, contentBodys);
			}
			
			//please note, here use remove() rather than get() - for memory consideration
			return contentBodys.remove(contentId, type);
			
		}
		//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		// private method
		private ContentBodys loadFile(String filename) {
			try {
				File file = new File(rootPath, filename);
				FileInputStream fis = new FileInputStream(file);
				ContentBodys contentBodys = (ContentBodys) xstream.fromXML(fis);
				IOUtils.closeQuietly(fis);
				
				log.info("Loaded content body file {}", file.getCanonicalPath());
				return contentBodys;
			} catch (Exception e) {
				Log.error("Unable to load content body file " + filename , e);
			}
			return null;
		}
		private String writeFile(Object obj, String filename) throws IOException {
			File file = new File(rootPath, filename);
			FileOutputStream fos = new FileOutputStream(file);
			OutputStreamWriter writer = new OutputStreamWriter(fos, Constants.UTF8);
			
			xstream.toXML(obj, writer);
			
			IOUtils.closeQuietly(writer);
			IOUtils.closeQuietly(fos);
			
			fileMap.put(file, canonialRootPath);
			
			return file.getCanonicalPath();
		}

	
	}
	public static class ContentBodys{
		
		//2M
		private static final int MAX_SIZE = 2 * 1024 * 1024;
		private transient int size = 0;
		
		// <contentID, body>
		private Map<Integer, String> pageBodys;
		private Map<Integer, String> draftBodys;
		private Map<Integer, String> historyBodys;
		
		
		public String remove(Integer contentId, PAGE_TYPE type){
			switch (type) {
			case PAGE:
				return pageBodys.remove(contentId);
			case DRAFT:
				return draftBodys.remove(contentId);
			case HISTORY:
				return historyBodys.remove(contentId);
			}
			
			return null;
		}
		public boolean add(Integer contentId, PAGE_TYPE type, String body){
			size += body.length();
			
			switch (type) {
			case PAGE:
				if (pageBodys == null) pageBodys = new HashMap<Integer, String>();
				pageBodys.put(contentId, body);
				break;
			case DRAFT:
				if (draftBodys == null) draftBodys = new HashMap<Integer, String>();
				draftBodys.put(contentId, body);
				break;
			case HISTORY:
				if (historyBodys == null) historyBodys = new HashMap<Integer, String>();
				historyBodys.put(contentId, body);
				break;
			}
			
			return (size > MAX_SIZE);
		}
		
		public void clear(){
			pageBodys = null;
			draftBodys = null;
			historyBodys = null;
			size = 0;
		}
	}
}
