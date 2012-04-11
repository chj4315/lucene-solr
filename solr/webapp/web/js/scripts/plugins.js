/*
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

var core_basepath = null;
var content_element = null;
var selected_type = null;
var context_path = null;
var active_context = null;
var changes = { count : {}, list : {} };

var compute_plugin_data = function( response, changeset )
{
  var types = [];
  var sort_table = {};
  var plugin_data = {};

  var types_obj = {};
  var plugin_key = null;

  for( var i = 0; i < response['solr-mbeans'].length; i++ )
  {
    if( !( i % 2 ) )
    {
      plugin_key = response['solr-mbeans'][i];
    }
    else
    {
      plugin_data[plugin_key] = response['solr-mbeans'][i];
    }
  }

  for( var key in plugin_data )
  {
    sort_table[key] = {
      url : [],
      component : [],
      handler : []
    };
    for( var part_key in plugin_data[key] )
    {
      if( 0 < part_key.indexOf( '.' ) )
      {
        types_obj[key] = true;
        sort_table[key]['handler'].push( part_key );
      }
      else if( 0 === part_key.indexOf( '/' ) )
      {
        types_obj[key] = true;
        sort_table[key]['url'].push( part_key );
      }
      else
      {
        types_obj[key] = true;
        sort_table[key]['component'].push( part_key );
      }
    }
  }

  for( var type in types_obj )
  {
    types.push( type );
  }
  types.sort();
            
  var result = {
    'plugin_data' : plugin_data
  };

  if( !changeset )
  {
    result['sort_table'] = sort_table;
    result['types'] = types;
  }

  return result;
};

var render_plugin_data = function( plugin_data, plugin_sort, types )
{
  var frame_element = $( '#frame', content_element );
  var navigation_element = $( '#navigation ul', content_element );
  var saved_xml = null;

  var navigation_content = [];
  for( var i = 0; i < types.length; i++ )
  {
    var type_url = active_context.params.splat[0] + '/' + active_context.params.splat[1] + '/' + types[i].toLowerCase();

    var navigation_markup = '<li class="' + types[i].toLowerCase().esc() + '">' +
                            '<a href="#/' + type_url + '" rel="' + types[i].esc() + '">' + types[i].esc();

    if( changes.count[types[i]] )
    {
      navigation_markup += ' <span>' + changes.count[types[i]].esc() + '</span>';
    }

    navigation_markup += '</a>' +
                         '</li>';

    navigation_content.push( navigation_markup );
  }

  navigation_content.push( '<li class="PLUGINCHANGES"><a href="#">Watch Changes</a></li>' );
  navigation_content.push( '<li class="RELOAD"><a href="#" onClick="window.location.reload()">Refresh Values</a></li>' );

  navigation_element
    .html( navigation_content.join( "\n" ) );
    
  $('.PLUGINCHANGES a').die( 'click' ).live( 'click', function(event) { 

    event.preventDefault();

    changes = { count : {}, list : {} }
    $( 'a > span', navigation_element ).remove();
    $( '.entry.changed', frame_element ).removeClass( 'changed' );

    $.blockUI({ message: $('#recording'), css: { width: '450px' } }); 
    
    $.ajax({
      type: 'GET',
      url: core_basepath + '/admin/mbeans?stats=true&wt=xml',
      dataType : 'text',
      success: function(data) {
        saved_xml = data;
      }
    }).error(function() {
      $.unblockUI(); 
      alert("error getting current status"); 
    });
  }); 

  $('#recording button').die( 'click' ).live( 'click', function() { 
    var data = { 
      'stats': "true", 
      'wt':    "json", 
      'diff':  "true", 
      'stream.body': saved_xml 
    };
    
    $.ajax({
      type: 'POST',
      url: core_basepath + '/admin/mbeans',
      dataType : 'json',
      data: data,
      success : function( response, text_status, xhr )
      {
        var beans = response['solr-mbeans'];
        for( var i = 0; i < beans.length; i += 2 )
        {
          var c = 0; var l = {}; 
          for( var j in beans[i+1] ) { c++; l[j] = true; }
          changes.count[beans[i]] = c;
          changes.list[beans[i]] = l;
        }
        console.debug( changes );

        var changed_data = compute_plugin_data( response, true );
        app.plugin_data = $.extend( true, {}, app.plugin_data, changed_data );
        
        render_plugin_data( app.plugin_data.plugin_data, app.plugin_data.sort_table, app.plugin_data.types );
      }
    });
    $.unblockUI(); 
  }); 
              
  $( 'a[href="' + context_path + '"]', navigation_element )
    .parent().addClass( 'current' );
            
  var content = '<ul>';
  for( var sort_key in plugin_sort[selected_type] )
  {
    plugin_sort[selected_type][sort_key].sort();
    var plugin_type_length = plugin_sort[selected_type][sort_key].length;
                
    for( var i = 0; i < plugin_type_length; i++ )
    {
      var bean = plugin_sort[selected_type][sort_key][i];
      var classes = [ 'entry' ];

      if( changes.list[selected_type] && changes.list[selected_type][bean] )
      {
        classes.push( 'changed' );
      }

      content += '<li class="' + classes.join( ' ' ) + '">' + "\n";
      content += '<a href="' + context_path + '?entry=' + bean.esc() + '">';
      content += '<span>' + bean.esc() + '</span>';
      content += '</a>' + "\n";
      content += '<ul class="detail">' + "\n";
                    
      var details = plugin_data[selected_type][ plugin_sort[selected_type][sort_key][i] ];
      for( var detail_key in details )
      {
        if( 'stats' !== detail_key )
        {
          var detail_value = details[detail_key];

          if( 'description' === detail_key )
          {
            // For list of components
            if(detail_value.match(/^Search using components: /)) {
              detail_value = detail_value
                .replace( /: /, ':<ul><li>' )
                .replace( /,/g, '</li><li>' ) +
                "</li></ul>";
            }
          }

          content += '<li><dl class="clearfix">' + "\n";
          content += '<dt>' + detail_key + ':</dt>' + "\n";
          if($.isArray(detail_value)) {
            $.each(detail_value, function(index, value) { 
              content += '<dd>' + value + '</dd>' + "\n";
            });
          }
          else {
            content += '<dd>' + detail_value + '</dd>' + "\n";
          }
          content += '</dl></li>' + "\n";
        }
        else if( 'stats' === detail_key && details[detail_key] )
        {
          content += '<li class="stats clearfix">' + "\n";
          content += '<span>' + detail_key + ':</span>' + "\n";
          content += '<ul>' + "\n";

          for( var stats_key in details[detail_key] )
          {
            var stats_value = details[detail_key][stats_key];

            if( 'readerDir' === stats_key )
            {
              stats_value = stats_value.replace( /@/g, '@&#8203;' );
            }

            content += '<li><dl class="clearfix">' + "\n";
            content += '<dt>' + stats_key + ':</dt>' + "\n";
            content += '<dd>' + stats_value + '</dd>' + "\n";
            content += '</dl></li>' + "\n";
          }

          content += '</ul></li>' + "\n";
        }
      }
                    
      content += '</ul>' + "\n";
    }
  }
  content += '</ul>' + "\n";

  frame_element
    .html( content );

  $( 'a[href="' + decodeURIComponent( active_context.path ) + '"]', frame_element )
    .parent().addClass( 'expanded' );
  
  // Try to make links for anything with http (but leave the rest alone)
  $( '.detail dd' ).each(function(index) {
    var txt = $(this).html();
    if(txt.indexOf("http") >= 0) {
      $(this).linker({
         className : 'linker',
      });
    }
  });
  
  // Add invisible whitespace after each slash
  $( '.detail a.linker' ).each(function(index) {
    $(this).html( $(this).html().replace( /\//g, '/&#8203;' ) );
  });
  
            
  $( '.entry', frame_element )
    .each
    (
      function( i, entry )
      {
        $( '.detail > li', entry ).not( '.stats' ).filter( ':even' )
          .addClass( 'odd' );

        $( '.stats li:odd', entry )
          .addClass( 'odd' );
      }
    );
};

sammy.bind
(
  'plugins_load',
  function( event, params )
  {
    var callback = function()
    {
      params.callback( app.plugin_data.plugin_data, app.plugin_data.sort_table, app.plugin_data.types );
    }
        
    if( app.plugin_data )
    {
      callback( app.plugin_data );
      return true;
    }

    var core_basepath = params.active_core.attr( 'data-basepath' );
    $.ajax
    (
      {
        url : core_basepath + '/admin/mbeans?stats=true&wt=json',
        dataType : 'json',
        beforeSend : function( xhr, settings )
        {
        },
        success : function( response, text_status, xhr )
        {
          app.plugin_data = compute_plugin_data( response );

          $.get
          (
            'tpl/plugins.html',
            function( template )
            {
              $( '#content' )
                .html( template );
                            
              callback( app.plugin_data );
            }
          );
        },
        error : function( xhr, text_status, error_thrown)
        {
        },
        complete : function( xhr, text_status )
        {
        }
      }
    );
  }
);

// #/:core/plugins/$type
sammy.get
(
  /^#\/([\w\d-]+)\/(plugins)\/(\w+)$/,
  function( context )
  {
    core_basepath = this.active_core.attr( 'data-basepath' );
    content_element = $( '#content' );
    selected_type = context.params.splat[2].toUpperCase();
    context_path = context.path.split( '?' ).shift();
    active_context = context;
    
    sammy.trigger
    (
      'plugins_load',
      {
        active_core : this.active_core,
        callback : render_plugin_data
      }
    );                
  }
);

// #/:core/plugins
sammy.get
(
  /^#\/([\w\d-]+)\/(plugins)$/,
  function( context )
  {
    delete app.plugin_data;

    sammy.trigger
    (
      'plugins_load',
      {
        active_core : this.active_core,
        callback :  function( plugin_data, plugin_sort, types )
        {
          context.redirect( context.path + '/' + types[0].toLowerCase() );
        }
      }
    );
  }
);