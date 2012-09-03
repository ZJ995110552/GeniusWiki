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
package com.edgenius.wiki.webapp.servlet;

import com.edgenius.core.repository.FileNode;
import com.edgenius.wiki.gwt.client.server.utils.GwtUtils;

/**
 * @author Dapeng.Ni
 */
public class FileObject {

	public String name;
	public String size;
	public String error;
	public String thumbnail_url;
	public String url;
	public String delete_type;
	public String delete_url;
	
	public static FileObject fromNode(FileNode node){
		FileObject item = new FileObject();
		item.name = node.getFilename();
		item.size = GwtUtils.convertHumanSize(node.getSize());
		
		return item;
	}
}
